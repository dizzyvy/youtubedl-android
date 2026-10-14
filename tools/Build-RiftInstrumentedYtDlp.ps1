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
import itertools
import os
import re
import sys
import threading
import time

_TRACE_ID = re.compile(r'^[a-f0-9]{8}$')
_SAFE_TYPE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]{0,63}$')
_SPANS = frozenset({
    'network_request',
    'extractor',
    'download_transfer',
    'postprocessor',
    'yt_dlp_execution',
})
_IDS = itertools.count(1)
_ID_LOCK = threading.Lock()


def _error_type(error):
    name = type(error).__name__ if error is not None else ''
    return name if _SAFE_TYPE.fullmatch(name) else 'Exception'


def _emit(event, operation_id, duration_ms, outcome, error_type='', category=''):
    trace_id = os.environ.get('RIFT_DOWNLOADER_TRACE_ID', '')
    if not _TRACE_ID.fullmatch(trace_id):
        return
    if category not in ('', 'request', 'extractor', 'download', 'postprocess', 'retry', 'process'):
        category = ''
    timestamp_ns = time.monotonic_ns()
    line = (
        f'RIFT_TIMING\t{trace_id}\t{event}\t{operation_id}\t{timestamp_ns}\t'
        f'{duration_ms}\t{outcome}\t{error_type}\t{category}\n'
    )
    try:
        sys.stderr.write(line)
        sys.stderr.flush()
    except OSError:
        pass


@contextlib.contextmanager
def span(name, category=''):
    if name not in _SPANS:
        yield
        return
    with _ID_LOCK:
        operation_id = next(_IDS)
    started_ns = time.monotonic_ns()
    _emit(f'{name}_started', operation_id, 0, 'started', category=category)
    try:
        yield
    except BaseException as error:
        duration_ms = (time.monotonic_ns() - started_ns) // 1_000_000
        _emit(f'{name}_finished', operation_id, duration_ms, 'failed', _error_type(error), category)
        raise
    else:
        duration_ms = (time.monotonic_ns() - started_ns) // 1_000_000
        _emit(f'{name}_finished', operation_id, duration_ms, 'success', category=category)


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

$requiredVersion = $false
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
            }
            'yt_dlp/networking/common.py' {
                $content = Replace-ExactlyOnce $content `
                    "from __future__ import annotations`n" `
                    "from __future__ import annotations`n`nfrom .. import rift_timing`n" `
                    $entry.FullName
                $content = Replace-ExactlyOnce $content `
                    "                response = handler.send(request)`n" `
                    "                with rift_timing.span('network_request', 'request'):`n                    response = handler.send(request)`n" `
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
                    "                    with rift_timing.span('extractor', 'extractor'):`n                        ie_result = self._real_extract(url)" `
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
    if ($instrumentedFiles.Count -ne 6) {
        throw "Expected six instrumented yt-dlp source files; found $($instrumentedFiles.Count)."
    }
    $helperEntry = $targetArchive.CreateEntry('yt_dlp/rift_timing.py', [IO.Compression.CompressionLevel]::Optimal)
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
[IO.File]::WriteAllBytes($OutputPath, $targetStream.ToArray())
$targetStream.Dispose()
Write-Output "Generated instrumented yt-dlp resource: $OutputPath"
