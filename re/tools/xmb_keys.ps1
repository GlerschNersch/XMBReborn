# Sends key presses to the RPCS3 VSH window: name[:holdMs] ... e.g.  Enter:1500 Backspace X
param([Parameter(Position = 0)] [string[]] $Keys, [int] $Gap = 700)

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
$vk = @{ Left = 0x25; Up = 0x26; Right = 0x27; Down = 0x28; X = 0x58; C = 0x43; V = 0x56; Z = 0x5A
         Enter = 0x0D; Space = 0x20; Backspace = 0x08; Q = 0x51; E = 0x45 }
$extended = @('Left', 'Up', 'Right', 'Down')
$h = (Get-Process rpcs3 | Where-Object MainWindowTitle -like '*vsh*' | Select-Object -First 1).MainWindowHandle
if (-not $h) { throw 'RPCS3 is not running vsh.self' }
[Win]::ShowWindow($h, 9) | Out-Null
# Windows only hands the foreground to a background process right after a key event.
[Win]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [Win]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
foreach ($spec in $Keys) {
    $name, $hold = $spec.Split(':'); $hold = if ($hold) { [int]$hold } else { 80 }
    $code = [byte]$vk[$name]; $scan = [byte][Win]::MapVirtualKey($code, 0)
    $flag = if ($extended -contains $name) { 1 } else { 0 }
    [Win]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 120
    [Win]::keybd_event($code, $scan, $flag, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $hold
    [Win]::keybd_event($code, $scan, $flag -bor 2, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $Gap
}
