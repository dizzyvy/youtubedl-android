param(
    [string]$SourcePath = (Join-Path $env:LOCALAPPDATA 'Temp\rift-nightly-ytdlp-2026.10.10.233141.bin'),
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\library\src\main\res\raw\ytdlp_instrumented')
)

$ErrorActionPreference = 'Stop'
$expectedSha256 = 'F40B6A9E4C5786360953911CAB5D2F87507626B8FB7BFEE3F8F1C2E5CF341FAE'
$downloadUrl = 'https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/2026.10.10.233141/yt-dlp'

if (-not (Test-Path -LiteralPath $SourcePath)) {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $SourcePath
}
if ((Get-FileHash -LiteralPath $SourcePath -Algorithm SHA256).Hash -ne $expectedSha256) {
    throw 'The yt-dlp source executable does not match the pinned nightly.'
}

$sourceBytes = [IO.File]::ReadAllBytes($SourcePath)
$zipOffset = -1
for ($i = 0; $i -lt $sourceBytes.Length - 4; $i++) {
    if ($sourceBytes[$i] -eq 0x50 -and $sourceBytes[$i + 1] -eq 0x4b -and
        $sourceBytes[$i + 2] -eq 0x03 -and $sourceBytes[$i + 3] -eq 0x04) {
        $zipOffset = $i
        break
    }
}
if ($zipOffset -lt 0) {
    throw 'The pinned yt-dlp executable does not contain a ZIP archive.'
}

$sourceStream = [IO.MemoryStream]::new($sourceBytes, $zipOffset, $sourceBytes.Length - $zipOffset)
$sourceArchive = [IO.Compression.ZipArchive]::new($sourceStream, [IO.Compression.ZipArchiveMode]::Read)
$targetStream = [IO.MemoryStream]::new()
$targetArchive = [IO.Compression.ZipArchive]::new($targetStream, [IO.Compression.ZipArchiveMode]::Create, $true)
$helper = @'
import contextlib
import contextvars
import itertools
import os
import re
import sys
import threading
import time

_TRACE_ID = re.compile(r'^[a-f0-9]{8}$')
_INVOCATION_ID = re.compile(r'^[a-f0-9]{32}$')
_SAFE_TYPE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]{0,63}$')
_SAFE_IDENTIFIER = re.compile(r'^[A-Za-z0-9_.-]{1,64}$')
_EXECUTION_KINDS = frozenset({'metadata', 'download', 'other'})
_SPANS = frozenset({
    'network_request',
    'response_processing',
    'response_transport',
    'response_body_read',
    'response_decode',
    'response_parse',
    'response_json_parse',
    'extractor',
    'extractor_initialization',
    'youtube_client_attempt',
    'po_token_lookup',
    'po_token_provider',
    'download_transfer',
    'postprocessor',
    'yt_dlp_execution',
})
_IDS = itertools.count(1)
_ID_LOCK = threading.Lock()
_STATE = contextvars.ContextVar('rift_timing_state', default=(None, None, None, None, None))


def _error_type(error):
    name = type(error).__name__ if error is not None else ''
    return name if _SAFE_TYPE.fullmatch(name) else 'Exception'


def _safe_identifier(value):
    return value if isinstance(value, str) and _SAFE_IDENTIFIER.fullmatch(value) else ''


def _emit(
        event, operation_id, value, outcome, error_type='', category='',
        invocation_id='', execution_kind='', parent_operation_id=None,
        extractor_key='', client='', provider='', related_operation_id=None,
        exit_code=None, exit_status=''):
    trace_id = os.environ.get('RIFT_DOWNLOADER_TRACE_ID', '')
    invocation_id = invocation_id or os.environ.get('RIFT_DOWNLOADER_INVOCATION_ID', '')
    execution_kind = execution_kind or os.environ.get('RIFT_DOWNLOADER_EXECUTION_KIND', '')
    if (
        not _TRACE_ID.fullmatch(trace_id)
        or not _INVOCATION_ID.fullmatch(invocation_id)
        or execution_kind not in _EXECUTION_KINDS
    ):
        return
    if category not in (
            '', 'request', 'response_processing', 'extractor', 'download',
            'postprocess', 'retry', 'process'):
        category = ''
    timestamp_ns = time.monotonic_ns()
    line = (
        f'RIFT_TIMING\t{trace_id}\t{event}\t{operation_id}\t{timestamp_ns}\t'
        f'{value}\t{outcome}\t{error_type}\t{category}\t{invocation_id}\t'
        f'{execution_kind}\t{parent_operation_id or ""}\t'
        f'{_safe_identifier(extractor_key)}\t{_safe_identifier(client)}\t'
        f'{_safe_identifier(provider)}\t{related_operation_id or ""}\t'
        f'{exit_code if exit_code is not None else ""}\t{exit_status or "-"}\n'
    )
    stderr = sys.stderr
    if stderr is None:
        return
    try:
        stderr.write(line)
        stderr.flush()
    except (OSError, ValueError):
        pass


@contextlib.contextmanager
def span(
        name, category='', extractor_key=None, client=None, provider=None,
        related_operation_id=None):
    trace_id = os.environ.get('RIFT_DOWNLOADER_TRACE_ID', '')
    invocation_id = os.environ.get('RIFT_DOWNLOADER_INVOCATION_ID', '')
    execution_kind = os.environ.get('RIFT_DOWNLOADER_EXECUTION_KIND', '')
    if (
        name not in _SPANS
        or not _TRACE_ID.fullmatch(trace_id)
        or not _INVOCATION_ID.fullmatch(invocation_id)
        or execution_kind not in _EXECUTION_KINDS
    ):
        yield None
        return
    parent_operation_id, parent_extractor, parent_client, parent_provider, parent_related_operation_id = _STATE.get()
    extractor_key = _safe_identifier(extractor_key) or parent_extractor
    client = _safe_identifier(client) or parent_client
    provider = _safe_identifier(provider) or parent_provider
    if related_operation_id is None:
        related_operation_id = parent_related_operation_id
    related_operation_id = (
        related_operation_id
        if type(related_operation_id) is int and related_operation_id >= 0
        else None
    )
    with _ID_LOCK:
        operation_id = next(_IDS)
    state_token = _STATE.set((operation_id, extractor_key, client, provider, related_operation_id))
    started_ns = time.monotonic_ns()
    try:
        _emit(
            f'{name}_started', operation_id, 0, 'started', category=category,
            invocation_id=invocation_id, execution_kind=execution_kind,
            parent_operation_id=parent_operation_id, extractor_key=extractor_key,
            client=client, provider=provider, related_operation_id=related_operation_id)
        try:
            yield operation_id
        except BaseException as error:
            duration_ms = (time.monotonic_ns() - started_ns) // 1_000_000
            outcome = 'failed'
            exit_code = None
            exit_status = ''
            if name == 'yt_dlp_execution' and isinstance(error, SystemExit):
                code = error.code
                if code is None:
                    outcome, exit_status = 'success', 'none'
                elif type(code) is bool:
                    exit_status = 'boolean'
                elif type(code) is int:
                    exit_code = code if -(2 ** 31) <= code < 2 ** 31 else None
                    exit_status = 'zero' if code == 0 else 'nonzero'
                    outcome = 'success' if code == 0 else 'failed'
                else:
                    exit_status = 'non_integer'
            _emit(
                f'{name}_finished', operation_id, duration_ms, outcome,
                _error_type(error), category, invocation_id, execution_kind,
                parent_operation_id, extractor_key, client, provider,
                related_operation_id, exit_code, exit_status)
            raise
        else:
            duration_ms = (time.monotonic_ns() - started_ns) // 1_000_000
            _emit(
                f'{name}_finished', operation_id, duration_ms, 'success',
                category=category, invocation_id=invocation_id,
                execution_kind=execution_kind, parent_operation_id=parent_operation_id,
                extractor_key=extractor_key, client=client, provider=provider,
                related_operation_id=related_operation_id)
    finally:
        _STATE.reset(state_token)


def report_retry(count, retries, error):
    outcome = 'exhausted' if count > retries else ('retrying' if count else 'not_retried')
    _emit('retry_reported', count, retries, outcome, _error_type(error), 'retry')
'@

function Replace-ExactlyOnce([string]$Text, [string]$Old, [string]$New, [string]$Name) {
    $count = ([regex]::Matches($Text, [regex]::Escape($Old))).Count
    if ($count -ne 1) {
        throw "Expected one instrumentation anchor in $Name; found $count."
    }
    return $Text.Replace($Old, $New)
}

function Wrap-BlockOnce(
    [string]$Text,
    [string]$StartLine,
    [string]$EndLine,
    [string]$WrapperLine,
    [string]$Name,
    [switch]$IncludeStart
) {
    $startToken = "$StartLine`n"
    $startCount = ([regex]::Matches($Text, [regex]::Escape($startToken))).Count
    $endCount = ([regex]::Matches($Text, [regex]::Escape($EndLine))).Count
    if ($startCount -ne 1 -or $endCount -ne 1) {
        throw "Expected one block boundary in $Name; found start=$startCount end=$endCount."
    }
    $startIndex = $Text.IndexOf($startToken, [StringComparison]::Ordinal)
    $bodyStart = if ($IncludeStart) { $startIndex } else { $startIndex + $startToken.Length }
    $endIndex = $Text.IndexOf($EndLine, $bodyStart, [StringComparison]::Ordinal)
    if ($endIndex -lt $bodyStart) {
        throw "Block boundaries were out of order in $Name."
    }
    $body = $Text.Substring($bodyStart, $endIndex - $bodyStart)
    $indentedLines = foreach ($line in $body.Split([char]10)) {
        if ($line.Length -eq 0) { $line } else { "    $line" }
    }
    $indentedBody = [string]::Join("`n", [string[]]$indentedLines)
    return $Text.Substring(0, $bodyStart) + $WrapperLine + "`n" + $indentedBody + $Text.Substring($endIndex)
}

$requiredVersion = $false
$helperTimestamp = $null
$instrumentedFiles = [Collections.Generic.HashSet[string]]::new()
try {
    foreach ($entry in $sourceArchive.Entries) {
        $entryBytesStream = [IO.MemoryStream]::new()
        $entryStream = $entry.Open()
        $entryStream.CopyTo($entryBytesStream)
        $entryStream.Dispose()
        $entryBytes = $entryBytesStream.ToArray()
        $entryBytesStream.Dispose()
        $content = [Text.Encoding]::UTF8.GetString($entryBytes)
        $isInstrumentedSource = $false

        switch ($entry.FullName) {
            'yt_dlp/version.py' {
                if ($content -notmatch "RELEASE_GIT_HEAD = '897341241f2bfb2439fe0367f4c7c9ccd29a3828'") {
                    throw 'The pinned runtime source revision did not match the expected commit.'
                }
                $requiredVersion = $true
                $helperTimestamp = $entry.LastWriteTime
            }
            'yt_dlp/networking/common.py' {
                $content = Replace-ExactlyOnce $content `
                    "from __future__ import annotations`n" `
                    "from __future__ import annotations`n`nfrom .. import rift_timing`n" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    "                response = handler.send(request)`n" `
                    "                with rift_timing.span('network_request', 'request') as request_operation_id:`n                    response = handler.send(request)`n                if request_operation_id is not None:`n                    response.extensions['rift_request_operation_id'] = request_operation_id`n" `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            'yt_dlp/extractor/common.py' {
                $content = Replace-ExactlyOnce $content `
                    'import time' `
                    "import time`n`nfrom .. import rift_timing" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '                    ie_result = self._real_extract(url)' `
                    "                    with rift_timing.span('extractor', 'extractor', extractor_key=type(self).__name__):`n                        ie_result = self._real_extract(url)" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    "                    self.initialize()`n" `
                    "                    with rift_timing.span('extractor_initialization', 'extractor', extractor_key=type(self).__name__):`n                        self.initialize()`n" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '            webpage_bytes = urlh.read()' `
                    "            with rift_timing.span('response_body_read', 'response_processing', related_operation_id=urlh.extensions.get('rift_request_operation_id')):`n                webpage_bytes = urlh.read()" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '        content = self.__decode_webpage(webpage_bytes, encoding, urlh.headers)' `
                    "        with rift_timing.span('response_decode', 'response_processing', related_operation_id=urlh.extensions.get('rift_request_operation_id')):`n            content = self.__decode_webpage(webpage_bytes, encoding, urlh.headers)" `
                    $entry.FullName
                $content = Wrap-BlockOnce $content `
                    '        urlh = self._request_webpage(url_or_request, video_id, note, errnote, fatal, data=data,' `
                    '        return (content, urlh)' `
                    "        with rift_timing.span('response_transport', 'response_processing'):" `
                    $entry.FullName `
                    -IncludeStart
                $content = Replace-ExactlyOnce $content `
                    '            return parse(self, content, video_id, transform_source=transform_source, fatal=fatal, errnote=errnote), urlh' `
                    "            request_operation_id = urlh.extensions.get('rift_request_operation_id')`n            with rift_timing.span('response_parse', 'response_processing', related_operation_id=request_operation_id):`n                parsed = parse(self, content, video_id, transform_source=transform_source, fatal=fatal, errnote=errnote)`n            return parsed, urlh" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    "            return json.loads(`n                json_string, cls=LenientJSONDecoder, strict=False, transform_source=transform_source, **parser_kwargs)`n" `
                    "            with rift_timing.span('response_json_parse', 'response_processing'):`n                return json.loads(`n                    json_string, cls=LenientJSONDecoder, strict=False, transform_source=transform_source, **parser_kwargs)`n" `
                    $entry.FullName
                $content = Wrap-BlockOnce $content `
                    '            res = self._download_webpage_handle(' `
                    '        def download_content(' `
                    "            with rift_timing.span('response_processing', 'response_processing'):" `
                    $entry.FullName `
                    -IncludeStart
                $content = Wrap-BlockOnce $content `
                    '        try_count = 0' `
                    '    def report_warning(self, msg, video_id=None, *args, only_once=False, **kwargs):' `
                    "        with rift_timing.span('response_processing', 'response_processing'):" `
                    $entry.FullName `
                    -IncludeStart
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            'yt_dlp/extractor/youtube/_video.py' {
                $content = Replace-ExactlyOnce $content `
                    'from .pot._director import initialize_pot_director' `
                    "from ... import rift_timing`nfrom .pot._director import initialize_pot_director" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '        return self._pot_director.get_po_token(pot_request)' `
                    "        with rift_timing.span('po_token_lookup', 'extractor', client=client):`n            return self._pot_director.get_po_token(pot_request)" `
                    $entry.FullName
                $content = Wrap-BlockOnce $content `
                    '            client, base_client, variant = _split_innertube_client(clients.pop())' `
                    '        prs.extend(deprioritized_prs)' `
                    "            with rift_timing.span('youtube_client_attempt', 'extractor', extractor_key=type(self).__name__, client=client):" `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            'yt_dlp/extractor/youtube/pot/_director.py' {
                $content = Replace-ExactlyOnce $content `
                    'from yt_dlp.utils import bug_reports_message, format_field, join_nonempty' `
                    "from .... import rift_timing`nfrom yt_dlp.utils import bug_reports_message, format_field, join_nonempty" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '                response = provider.request_pot(request.copy())' `
                    "                with rift_timing.span('po_token_provider', 'extractor', provider=provider.PROVIDER_KEY):`n                    response = provider.request_pot(request.copy())" `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            'yt_dlp/utils/_utils.py' {
                $content = Replace-ExactlyOnce $content `
                    'import base64' `
                    "from .. import rift_timing`n`nimport base64" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '        """Utility function for reporting retries"""' `
                    ('        """Utility function for reporting retries"""' +
                        "`n        rift_timing.report_retry(count, retries, e)") `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            'yt_dlp/downloader/common.py' {
                $content = Replace-ExactlyOnce $content `
                    'import contextlib' `
                    "from .. import rift_timing`n`nimport contextlib" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '        ret = self.real_download(filename, info_dict)' `
                    "        with rift_timing.span('download_transfer', 'download'):`n            ret = self.real_download(filename, info_dict)" `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            'yt_dlp/postprocessor/common.py' {
                $content = Replace-ExactlyOnce $content `
                    'import functools' `
                    "import functools`n`nfrom .. import rift_timing" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    '            ret = func(self, info, *args, **kwargs)' `
                    "            with rift_timing.span('postprocessor', 'postprocess'):`n                ret = func(self, info, *args, **kwargs)" `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
            '__main__.py' {
                $content = Replace-ExactlyOnce $content `
                    'import yt_dlp' `
                    "import yt_dlp`nfrom yt_dlp import rift_timing" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    "if __name__ == '__main__':`n    yt_dlp.main()" `
                    "if __name__ == '__main__':`n    with rift_timing.span('yt_dlp_execution', 'process'):`n        yt_dlp.main()" `
                    $entry.FullName
                [void]$instrumentedFiles.Add($entry.FullName)
                $isInstrumentedSource = $true
            }
        }

        $targetEntry = $targetArchive.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Optimal)
        $targetEntry.LastWriteTime = $entry.LastWriteTime
        $targetEntryStream = $targetEntry.Open()
        $targetBytes = if ($isInstrumentedSource) {
            [Text.UTF8Encoding]::new($false).GetBytes($content)
        } else {
            $entryBytes
        }
        if ($null -eq $targetBytes) {
            $targetBytes = [byte[]]::new(0)
        }
        $targetEntryStream.Write($targetBytes, 0, $targetBytes.Length)
        $targetEntryStream.Dispose()
    }

    if (-not $requiredVersion) {
        throw 'The pinned yt-dlp version metadata was not found.'
    }
    if ($instrumentedFiles.Count -ne 8) {
        throw "Expected eight instrumented yt-dlp source files; found $($instrumentedFiles.Count)."
    }
    if ($null -eq $helperTimestamp) {
        throw 'The pinned yt-dlp timestamp metadata was not found.'
    }
    $helperEntry = $targetArchive.CreateEntry('yt_dlp/rift_timing.py', [IO.Compression.CompressionLevel]::Optimal)
    $helperEntry.LastWriteTime = $helperTimestamp
    $helperWriter = [IO.StreamWriter]::new($helperEntry.Open(), [Text.UTF8Encoding]::new($false))
    $helperWriter.Write($helper)
    $helperWriter.Dispose()
} finally {
    $sourceArchive.Dispose()
    $sourceStream.Dispose()
    $targetArchive.Dispose()
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$temporaryOutputPath = Join-Path $outputDirectory ('.' + [guid]::NewGuid().ToString('N') + '.tmp')
$backupOutputPath = Join-Path $outputDirectory ('.' + [guid]::NewGuid().ToString('N') + '.bak')
try {
    [IO.File]::WriteAllBytes($temporaryOutputPath, $targetStream.ToArray())
    if ([IO.File]::Exists($OutputPath)) {
        [IO.File]::Replace($temporaryOutputPath, $OutputPath, $backupOutputPath)
        [IO.File]::Delete($backupOutputPath)
    } else {
        [IO.File]::Move($temporaryOutputPath, $OutputPath)
    }
} finally {
    foreach ($temporaryPath in @($temporaryOutputPath, $backupOutputPath)) {
        if ([IO.File]::Exists($temporaryPath)) {
            [IO.File]::Delete($temporaryPath)
        }
    }
    $targetStream.Dispose()
}
Write-Output "Generated instrumented yt-dlp resource: $OutputPath"
