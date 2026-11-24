param([string]$DesktopDirectory = [Environment]::GetFolderPath('Desktop'))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$appTitle = 'Projects Journal'
$shortcutPath = Join-Path $DesktopDirectory "$appTitle.lnk"
if (Test-Path -LiteralPath $shortcutPath) {
    throw "A shortcut already exists at $shortcutPath. It has not been replaced."
}
$assets = Join-Path $root '.local\launcher'
$null = New-Item -ItemType Directory -Path $assets -Force
$iconPath = Join-Path $assets 'projects-journal.ico'

# Draw the notebook mark from public/favicon.svg.
Add-Type -AssemblyName System.Drawing
$bitmap = New-Object Drawing.Bitmap(64, 64)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$pen = New-Object Drawing.Pen([Drawing.Color]::White, 3.5)
$stream = $null
try {
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#385b7c'))
    $graphics.DrawRectangle($pen, 20, 12, 29, 40)
    foreach ($y in 22, 32, 42) {
        $graphics.DrawLine($pen, 15, $y, 24, $y)
    }
    # ICO supports a PNG payload, so no native icon handles need to be retained.
    $png = New-Object IO.MemoryStream
    try {
        $bitmap.Save($png, [Drawing.Imaging.ImageFormat]::Png)
        $payload = $png.ToArray()
        $stream = [IO.File]::Create($iconPath)
        $writer = New-Object IO.BinaryWriter($stream)
        $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]1)
        $writer.Write([byte]64); $writer.Write([byte]64); $writer.Write([uint16]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$payload.Length); $writer.Write([uint32]22)
        $writer.Write($payload)
        $writer.Flush()
    } finally { $png.Dispose() }
} finally {
    if ($stream) { $stream.Dispose() }
    $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
}
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
$shortcut.Arguments = '"' + (Join-Path $root 'Projects Journal.vbs') + '"'
$shortcut.WorkingDirectory = $root
$shortcut.IconLocation = "$iconPath,0"
$shortcut.Description = "Открыть «$appTitle»"
$shortcut.Save()
Write-Output "Created: $shortcutPath"
