# D-Agent PC installer for Windows 10/11.
# Open PowerShell (no admin needed) and run:
#   irm https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.ps1 | iex
#
# What it does: installs Python if it's missing, downloads D-Agent PC to %USERPROFILE%\.dagent-pc,
# starts it now and with every sign-in (hidden, no window), opens ports 8787 (D-Agent PC) and
# 1234 (LM Studio) on private networks, and shows the pairing code for the phone.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Branch = 'claude/project-thread-7jgi6v'
$Raw = "https://raw.githubusercontent.com/deezi74/D-Agent/$Branch/pc-worker"
$Dir = Join-Path $env:USERPROFILE '.dagent-pc'
$Script = Join-Path $Dir 'dagent_pc.py'
$Port = 8787

function Say($text, $color = 'Cyan') { Write-Host "==> $text" -ForegroundColor $color }

function Find-Python {
    $candidates = @()
    foreach ($name in @('py', 'python', 'python3')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { $candidates += $cmd.Source }
    }
    $candidates += Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $candidates += Get-ChildItem "$env:ProgramFiles\Python3*\python.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    foreach ($c in $candidates) {
        try {
            # The Microsoft Store "python" stub prints nothing, so it fails this check.
            $exe = & $c -c "import sys; print(sys.executable if sys.version_info >= (3, 9) else '')" 2>$null
            if ($exe -and (Test-Path $exe)) { return $exe.Trim() }
        } catch { }
    }
    return $null
}

New-Item -ItemType Directory -Force -Path $Dir | Out-Null

Say 'Checking for Python 3.9 or newer...'
$Python = Find-Python
if (-not $Python) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Say 'Python is missing and winget is not available. Install Python 3.12 from https://www.python.org/downloads/ (tick "Add to PATH"), then run this again.' 'Red'
        return
    }
    Say 'Installing Python 3.12 with winget (just for you, no admin)...'
    winget install -e --id Python.Python.3.12 --scope user --silent --accept-package-agreements --accept-source-agreements | Out-Host
    $Python = Find-Python
    if (-not $Python) { Say 'Python installed but was not found yet. Close PowerShell, open a new one and run this again.' 'Red'; return }
}
$Pythonw = Join-Path (Split-Path $Python) 'pythonw.exe'
if (-not (Test-Path $Pythonw)) { $Pythonw = $Python }
Say "Using $Python"

Say 'Downloading D-Agent PC...'
Invoke-WebRequest -UseBasicParsing "$Raw/dagent_pc.py" -OutFile "$Script.new"
& $Python -c "import ast,sys; ast.parse(open(sys.argv[1], encoding='utf-8').read())" "$Script.new"
if ($LASTEXITCODE -ne 0) { Say 'The download looks broken. Try again in a minute.' 'Red'; return }
Move-Item -Force "$Script.new" $Script

Say 'Stopping any copy that is already running...'
Get-CimInstance Win32_Process -Filter "Name like 'python%'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*dagent_pc.py*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Say 'Starting D-Agent PC with Windows...'
$Startup = [Environment]::GetFolderPath('Startup')
$Shell = New-Object -ComObject WScript.Shell
$Link = $Shell.CreateShortcut((Join-Path $Startup 'D-Agent PC.lnk'))
$Link.TargetPath = $Pythonw
$Link.Arguments = "`"$Script`""
$Link.WorkingDirectory = $Dir
$Link.WindowStyle = 7
$Link.Description = 'D-Agent PC: the always-on computer for the D-Agent app'
$Link.Save()

Say 'Allowing the phone to connect (Windows asks for permission once)...'
$rules = "New-NetFirewallRule -DisplayName 'D-Agent PC' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private,Domain -ErrorAction SilentlyContinue | Out-Null; " +
         "New-NetFirewallRule -DisplayName 'LM Studio server (D-Agent)' -Direction Inbound -Protocol TCP -LocalPort 1234 -Action Allow -Profile Private,Domain -ErrorAction SilentlyContinue | Out-Null"
$haveRule = Get-NetFirewallRule -DisplayName 'D-Agent PC' -ErrorAction SilentlyContinue
if (-not $haveRule) {
    try { Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "-NoProfile -Command $rules" }
    catch { Say "Skipped the firewall step. If the phone can't connect, allow TCP port $Port in Windows Firewall." 'Yellow' }
}

$public = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.NetworkCategory -eq 'Public' }
if ($public) {
    Say "Your network '$($public[0].Name)' is set to Public, which blocks the phone. In Settings > Network & internet > (your Wi-Fi or Ethernet), set Network profile to Private." 'Yellow'
}

$answer = Read-Host 'Keep this PC from sleeping while it is plugged in, so tasks keep running? (Y/n)'
if ($answer -notmatch '^[nN]') { powercfg /change standby-timeout-ac 0; Say 'Sleep on AC power turned off. (Undo: Settings > System > Power.)' }

Start-Process -FilePath $Pythonw -ArgumentList "`"$Script`"" -WorkingDirectory $Dir -WindowStyle Hidden
Start-Sleep -Seconds 2
& $Python $Script --code
Say 'Done. D-Agent PC runs in the background and starts with Windows.' 'Green'
Say "Logs: $Dir\dagent-pc.log    Settings: $Dir\config.json" 'Gray'
