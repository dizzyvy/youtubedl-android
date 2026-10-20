import contextlib
import io
import os
from pathlib import Path
import re
import sys
import types
import zipfile
from unittest.mock import patch


RESOURCE = (
    Path(__file__).resolve().parents[1]
    / "library"
    / "src"
    / "main"
    / "res"
    / "raw"
    / "ytdlp_instrumented"
)
EXPECTED_VERSION = "2026.10.10.233141"
EXPECTED_REVISION = "897341241f2bfb2439fe0367f4c7c9ccd29a3828"
ZIP_LOCAL_FILE_HEADER = b"PK\x03\x04"

REQUIRED_INSTRUMENTATION = {
    "__main__.py": (
        ("with rift_timing.span('yt_dlp_execution', 'process'):", 1),
        ("        yt_dlp.main()", 1),
    ),
    "yt_dlp/networking/common.py": (
        ("with rift_timing.span('network_request', 'request') as request_operation_id:", 1),
        ("response.extensions['rift_request_operation_id'] = request_operation_id", 1),
    ),
    "yt_dlp/extractor/common.py": (
        ("with rift_timing.span('extractor', 'extractor', extractor_key=type(self).__name__):", 1),
        ("with rift_timing.span('extractor_initialization', 'extractor', extractor_key=type(self).__name__):", 1),
        ("with rift_timing.span('response_transport', 'response_processing'):", 1),
        ("with rift_timing.span('response_body_read', 'response_processing', related_operation_id=urlh.extensions.get('rift_request_operation_id')):", 1),
        ("with rift_timing.span('response_decode', 'response_processing', related_operation_id=urlh.extensions.get('rift_request_operation_id')):", 1),
        ("with rift_timing.span('response_parse', 'response_processing', related_operation_id=request_operation_id):", 1),
        ("with rift_timing.span('response_json_parse', 'response_processing'):", 1),
        ("with rift_timing.span('response_processing', 'response_processing'):", 2),
    ),
    "yt_dlp/extractor/youtube/_video.py": (
        ("with rift_timing.span('po_token_lookup', 'extractor', client=client):", 1),
        ("with rift_timing.span('youtube_client_attempt', 'extractor', extractor_key=type(self).__name__, client=client):", 1),
    ),
    "yt_dlp/extractor/youtube/pot/_director.py": (
        ("with rift_timing.span('po_token_provider', 'extractor', provider=provider.PROVIDER_KEY):", 1),
    ),
    "yt_dlp/utils/_utils.py": (
        ("rift_timing.report_retry(count, retries, e)", 1),
    ),
    "yt_dlp/downloader/common.py": (
        ("with rift_timing.span('download_transfer', 'download'):", 1),
    ),
    "yt_dlp/postprocessor/common.py": (
        ("with rift_timing.span('postprocessor', 'postprocess'):", 1),
    ),
}


class ValidationError(Exception):
    pass


def open_runtime_archive(data):
    offset = data.find(ZIP_LOCAL_FILE_HEADER)
    while offset >= 0:
        stream = io.BytesIO(data[offset:])
        try:
            archive = zipfile.ZipFile(stream)
        except zipfile.BadZipFile:
            stream.close()
        else:
            names = set(archive.namelist())
            if {"yt_dlp/version.py", "yt_dlp/rift_timing.py"} <= names:
                return archive, offset
            archive.close()
        offset = data.find(ZIP_LOCAL_FILE_HEADER, offset + 1)
    raise ValidationError("Generated runtime does not contain a valid yt-dlp ZIP archive.")


def validate_pinned_version(archive):
    version_source = archive.read("yt_dlp/version.py").decode("utf-8")
    version_match = re.search(r"^__version__ = ['\"]([^'\"]+)['\"]$", version_source, re.M)
    revision_match = re.search(
        r"^RELEASE_GIT_HEAD = ['\"]([^'\"]+)['\"]$", version_source, re.M
    )
    if version_match is None or version_match.group(1) != EXPECTED_VERSION:
        raise ValidationError("Embedded yt-dlp version does not match the exact pin.")
    if revision_match is None or revision_match.group(1) != EXPECTED_REVISION:
        raise ValidationError("Embedded yt-dlp source revision does not match the exact pin.")


def validate_instrumentation(archive):
    for filename, markers in REQUIRED_INSTRUMENTATION.items():
        try:
            source = archive.read(filename).decode("utf-8")
        except KeyError:
            raise ValidationError(f"Missing transformed source entry: {filename}.") from None
        except UnicodeDecodeError:
            raise ValidationError(f"Transformed source is not valid UTF-8: {filename}.") from None
        for marker, expected_count in markers:
            if source.count(marker) != expected_count:
                raise ValidationError(
                    f"Instrumentation marker check failed for {filename}: {marker.split('(')[0]}."
                )


def validate_timing_helper(archive):
    try:
        helper_source = archive.read("yt_dlp/rift_timing.py")
    except KeyError:
        raise ValidationError("Missing timing helper source entry.") from None
    helper = types.ModuleType("rift_timing_validation")
    exec(compile(helper_source, "yt_dlp/rift_timing.py", "exec"), helper.__dict__)

    def finished_event(error):
        output = io.StringIO()
        environment = {
            "RIFT_DOWNLOADER_TRACE_ID": "a1b2c3d4",
            "RIFT_DOWNLOADER_INVOCATION_ID": "0" * 32,
            "RIFT_DOWNLOADER_EXECUTION_KIND": "metadata",
        }
        with patch.dict(os.environ, environment), contextlib.redirect_stderr(output):
            try:
                with helper.span("yt_dlp_execution", "process"):
                    raise error
            except BaseException as caught:
                if caught is not error:
                    raise AssertionError("Timing helper changed the raised exception.")
            else:
                raise AssertionError("Timing helper did not re-raise the exception.")

        lines = output.getvalue().splitlines()
        if len(lines) != 2:
            raise AssertionError("Timing helper did not emit one start and one finish event.")
        started, finished = (line.split("\t") for line in lines)
        if (
            len(started) != 18
            or len(finished) != 18
            or started[2] != "yt_dlp_execution_started"
            or finished[2] != "yt_dlp_execution_finished"
            or started[3] != finished[3]
            or started[9] != environment["RIFT_DOWNLOADER_INVOCATION_ID"]
            or started[10] != "metadata"
        ):
            raise AssertionError("Timing helper emitted an invalid 18-field event pair.")
        return finished, output.getvalue()

    expected_system_exit = (
        (None, "success", "", "none"),
        (0, "success", "0", "zero"),
        (3, "failed", "3", "nonzero"),
        (-3, "failed", "-3", "nonzero"),
        (2**40, "failed", "", "nonzero"),
        (False, "failed", "", "boolean"),
        (True, "failed", "", "boolean"),
        ("private-message-sentinel", "failed", "", "non_integer"),
    )
    for code, outcome, exit_code, exit_status in expected_system_exit:
        event, emitted = finished_event(SystemExit(code))
        if (
            event[6] != outcome
            or event[7] != "SystemExit"
            or event[16] != exit_code
            or event[17] != exit_status
        ):
            raise AssertionError("SystemExit status classification did not match expectations.")
        if code == "private-message-sentinel" and code in emitted:
            raise AssertionError("SystemExit message text leaked into the timing event.")

    event, emitted = finished_event(RuntimeError("private-message-sentinel"))
    if (
        event[6] != "failed"
        or event[7] != "RuntimeError"
        or event[16] != ""
        or event[17] != "-"
        or "private-message-sentinel" in emitted
    ):
        raise AssertionError("Unexpected exception was not emitted as a sanitized failure.")

    class FailingStderr:
        def __init__(self, error, fail_on_operation, fail_on_number):
            self.error = error
            self.fail_on_operation = fail_on_operation
            self.fail_on_number = fail_on_number
            self.write_count = 0
            self.flush_count = 0

        def write(self, value):
            self.write_count += 1
            if (
                self.fail_on_operation == "write"
                and self.write_count == self.fail_on_number
            ):
                raise self.error
            return len(value)

        def flush(self):
            self.flush_count += 1
            if (
                self.fail_on_operation == "flush"
                and self.flush_count == self.fail_on_number
            ):
                raise self.error

    environment = {
        "RIFT_DOWNLOADER_TRACE_ID": "a1b2c3d4",
        "RIFT_DOWNLOADER_INVOCATION_ID": "0" * 32,
        "RIFT_DOWNLOADER_EXECUTION_KIND": "metadata",
    }
    for error_type in (OSError, ValueError):
        for fail_on_operation, fail_on_number in (
            ("write", 1),
            ("flush", 1),
            ("write", 2),
            ("flush", 2),
        ):
            original_error = RuntimeError("original downloader failure")
            initial_state = helper._STATE.get()
            with patch.dict(os.environ, environment), contextlib.redirect_stderr(io.StringIO()):
                with helper.span("extractor", "extractor", extractor_key="parent"):
                    parent_state = helper._STATE.get()
                    stderr = FailingStderr(
                        error_type("stderr unavailable"),
                        fail_on_operation,
                        fail_on_number,
                    )
                    with contextlib.redirect_stderr(stderr):
                        try:
                            with helper.span("yt_dlp_execution", "process"):
                                raise original_error
                        except BaseException as caught:
                            if caught is not original_error:
                                raise AssertionError("Logging failure replaced the original exception.")
                        else:
                            raise AssertionError(
                                "Timing helper did not re-raise the original exception."
                            )
                    if helper._STATE.get() != parent_state:
                        raise AssertionError(
                            "Timing helper did not restore parent context after logging failure."
                        )
            if helper._STATE.get() != initial_state:
                raise AssertionError("Timing helper did not restore context after logging failure.")

    initial_state = helper._STATE.get()
    stderr = FailingStderr(ValueError("stderr unavailable"), "write", 1)
    with patch.dict(os.environ, environment), contextlib.redirect_stderr(stderr):
        with helper.span("yt_dlp_execution", "process"):
            result = "operation-result"
    if result != "operation-result" or helper._STATE.get() != initial_state:
        raise AssertionError("Logging failure changed the operation result or context.")

    with patch.dict(os.environ, environment), contextlib.redirect_stderr(None):
        with helper.span("yt_dlp_execution", "process"):
            result = "operation-result"
    if result != "operation-result" or helper._STATE.get() != initial_state:
        raise AssertionError("Missing stderr changed the operation result or context.")


def main():
    try:
        data = RESOURCE.read_bytes()
    except OSError:
        raise ValidationError(f"Unable to read generated runtime resource: {RESOURCE.name}.") from None
    archive, offset = open_runtime_archive(data)
    try:
        validate_pinned_version(archive)
        validate_instrumentation(archive)
        python_entries = [
            entry for entry in archive.infolist() if entry.filename.endswith(".py")
        ]
        if not python_entries:
            raise ValidationError("Generated runtime contains no Python source entries.")
        for entry in python_entries:
            try:
                compile(archive.read(entry), entry.filename, "exec")
            except (SyntaxError, UnicodeDecodeError):
                raise ValidationError(
                    f"Embedded Python source failed syntax compilation: {entry.filename}."
                ) from None
        validate_timing_helper(archive)
    finally:
        archive.close()

    print(
        f"Validated {len(python_entries)} embedded Python source files "
        f"and timing protocol (ZIP offset {offset})."
    )


if __name__ == "__main__":
    try:
        main()
    except ValidationError as error:
        print(f"Instrumented yt-dlp validation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from None
    except Exception as error:
        print(
            f"Instrumented yt-dlp validation failed unexpectedly: {type(error).__name__}.",
            file=sys.stderr,
        )
        raise SystemExit(1) from None
