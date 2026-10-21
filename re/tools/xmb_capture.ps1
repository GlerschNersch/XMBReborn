# Records the RPCS3 VSH window while sending timed key presses, for measuring XMB motion.
# Needs RPCS3 running vsh.self with a Keyboard pad profile (arrows = D-pad, X = Cross).
# Writes <Out>.mp4 plus <Out>.keys.csv (key, seconds since recording start).
param(
    [Parameter(Mandatory)] [string] $Out,
    [string[]] $Keys = @('Right', 'Right', 'Left', 'Left', 'Down', 'Down', 'Up', 'Up'),
    [double] $Gap = 1.5,
    [double] $Lead = 1.5,
    [int] $Fps = 60
)

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Win {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint type);
}
'@

$vk = @{ Left = 0x25; Up = 0x26; Right = 0x27; Down = 0x28; X = 0x58; C = 0x43; V = 0x56; Z = 0x5A; Enter = 0x0D; Space = 0x20 }
$extended = @('Left', 'Up', 'Right', 'Down')

$proc = Get-Process rpcs3 | Where-Object MainWindowTitle -like '*vsh*' | Select-Object -First 1
if (-not $proc) { throw 'RPCS3 is not running vsh.self' }
$h = $proc.MainWindowHandle
[Win]::ShowWindow($h, 9) | Out-Null
# Windows only hands the foreground to a background process right after a key event.
[Win]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [Win]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
[Win]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 300

$duration = $Lead + $Keys.Count * $Gap + 1.5
$ffArgs = @('-hide_banner', '-loglevel', 'error', '-y', '-f', 'gdigrab', '-framerate', "$Fps",
    '-i', "hwnd=$h", '-t', "$duration", '-c:v', 'libx264', '-preset', 'ultrafast', '-qp', '0', "$Out.mp4")
$ff = Start-Process ffmpeg -ArgumentList $ffArgs -PassThru -NoNewWindow
$clock = [Diagnostics.Stopwatch]::StartNew()

$log = @('key,t')
Start-Sleep -Milliseconds ([int]($Lead * 1000))
foreach ($k in $Keys) {
    $code = [byte]$vk[$k]
    $scan = [byte][Win]::MapVirtualKey($code, 0)
    $flag = if ($extended -contains $k) { 1 } else { 0 }
    [Win]::SetForegroundWindow($h) | Out-Null
    $log += "$k,$($clock.Elapsed.TotalSeconds.ToString('0.000'))"
    [Win]::keybd_event($code, $scan, $flag, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [Win]::keybd_event($code, $scan, $flag -bor 2, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds ([int]($Gap * 1000 - 60))
}
$ff.WaitForExit()
$log | Set-Content -Encoding utf8 "$Out.keys.csv"
"recorded $Out.mp4 ($duration s)"
