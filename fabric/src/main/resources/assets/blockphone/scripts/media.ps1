# BlockPhone: мост к системным медиа Windows (System Media Transport Controls).
# Управляет приложением Spotify на этом компьютере (его сессией в медиа Windows).
# Запускает мод (music/media/WindowsMedia.java): в stdout — строка JSON с состоянием раз в ~0,4 с,
# из stdin — команды: toggle | play | pause | next | prev | seek <мс> | shuffle <0|1> | repeat <0|1|2>.
# Закрытие stdin — выход.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

function Emit($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 3))
    [Console]::Out.Flush()
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime] | Out-Null
    [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null
} catch {
    Emit @{ type = 'error'; message = "init: $($_.Exception.Message)" }
    exit 1
}

function Await($operation, [Type]$resultType, [int]$timeout = 3000) {
    $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait($timeout)) { return $null }
    return $task.Result
}

try {
    $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
    $manager = Await ($managerType::RequestAsync()) $managerType 10000
} catch {
    Emit @{ type = 'error'; message = "manager: $($_.Exception.Message)" }
    exit 1
}
if ($null -eq $manager) {
    Emit @{ type = 'error'; message = 'manager: timeout' }
    exit 1
}

$script:thumbKey = ''
$script:thumbTries = 0

function Read-Thumb($reference) {
    $stream = Await ($reference.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    if ($null -eq $stream) { return $null }
    try {
        $size = [uint32]$stream.Size
        if ($size -eq 0 -or $size -gt 4MB) { return $null }
        $reader = [Windows.Storage.Streams.DataReader]::new($stream.GetInputStreamAt(0))
        $loaded = Await ($reader.LoadAsync($size)) ([uint32])
        if ($null -eq $loaded) { return $null }
        $bytes = New-Object byte[] $loaded
        $reader.ReadBytes($bytes)
        $reader.Dispose()
        return [Convert]::ToBase64String($bytes)
    } finally {
        $stream.Dispose()
    }
}

# Сессия Spotify (приложение для ПК или из Microsoft Store); другие плееры не трогаем.
function Get-Spotify {
    foreach ($s in $manager.GetSessions()) {
        if ([string]$s.SourceAppUserModelId -like '*spotify*') { return $s }
    }
    return $null
}

function Get-State {
    $session = Get-Spotify
    if ($null -eq $session) { return @{ type = 'state'; active = $false } }
    $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    $info = $session.GetPlaybackInfo()
    $timeline = $session.GetTimelineProperties()
    $title = if ($props) { $props.Title } else { '' }
    $artist = if ($props) { $props.Artist } else { '' }
    $album = if ($props) { $props.AlbumTitle } else { '' }
    $key = "$($session.SourceAppUserModelId)|$title|$artist"
    $thumb = $null
    if ($key -ne $script:thumbKey) {
        $script:thumbTries = 0
    }
    # Обложку — один раз на трек (приложения иногда подгружают её позже — несколько попыток).
    if ($key -ne $script:thumbKey -and $props -and $props.Thumbnail -and $script:thumbTries -lt 5) {
        $script:thumbTries++
        try {
            $thumb = Read-Thumb $props.Thumbnail
        } catch {
            $thumb = $null
        }
        if ($thumb) { $script:thumbKey = $key }
    }
    $controls = $info.Controls
    $updated = 0
    try { $updated = ([DateTimeOffset]$timeline.LastUpdatedTime).ToUnixTimeMilliseconds() } catch { }
    return @{
        type = 'state'; active = $true; app = [string]$session.SourceAppUserModelId; title = [string]$title; artist = [string]$artist
        album = [string]$album; status = [string]$info.PlaybackStatus
        position = [long]($timeline.Position.TotalMilliseconds - $timeline.StartTime.TotalMilliseconds)
        duration = [long]($timeline.EndTime.TotalMilliseconds - $timeline.StartTime.TotalMilliseconds)
        updated = [long]$updated; now = [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
        shuffle = [bool]$info.IsShuffleActive; repeat = [string]$info.AutoRepeatMode
        canNext = [bool]$controls.IsNextEnabled; canPrev = [bool]$controls.IsPreviousEnabled
        canSeek = [bool]$controls.IsPlaybackPositionEnabled; canShuffle = [bool]$controls.IsShuffleEnabled
        canRepeat = [bool]$controls.IsRepeatEnabled; key = $key; thumb = $thumb
    }
}

function Run-Command([string]$line) {
    $session = Get-Spotify
    if ($null -eq $session) { return }
    $parts = $line.Trim().Split(' ')
    switch ($parts[0]) {
        'toggle' { Await ($session.TryTogglePlayPauseAsync()) ([bool]) | Out-Null }
        'play' { Await ($session.TryPlayAsync()) ([bool]) | Out-Null }
        'pause' { Await ($session.TryPauseAsync()) ([bool]) | Out-Null }
        'next' { Await ($session.TrySkipNextAsync()) ([bool]) | Out-Null }
        'prev' { Await ($session.TrySkipPreviousAsync()) ([bool]) | Out-Null }
        'seek' {
            $timeline = $session.GetTimelineProperties()
            $ticks = [long]$parts[1] * 10000 + $timeline.StartTime.Ticks
            Await ($session.TryChangePlaybackPositionAsync($ticks)) ([bool]) | Out-Null
        }
        'shuffle' { Await ($session.TryChangeShuffleActiveAsync($parts[1] -eq '1')) ([bool]) | Out-Null }
        'repeat' {
            $mode = [Windows.Media.MediaPlaybackAutoRepeatMode, Windows.Media, ContentType=WindowsRuntime]
            Await ($session.TryChangeAutoRepeatModeAsync([Enum]::ToObject($mode, [int]$parts[1]))) ([bool]) | Out-Null
        }
    }
}

$stdin = [Console]::In
$pending = $stdin.ReadLineAsync()
while ($true) {
    while ($pending.IsCompleted) {
        $line = $pending.Result
        if ($null -eq $line) { exit 0 }
        try { Run-Command $line } catch { Emit @{ type = 'error'; message = "command: $($_.Exception.Message)" } }
        $pending = $stdin.ReadLineAsync()
    }
    try {
        Emit (Get-State)
    } catch {
        Emit @{ type = 'error'; message = "state: $($_.Exception.Message)" }
    }
    Start-Sleep -Milliseconds 400
}
