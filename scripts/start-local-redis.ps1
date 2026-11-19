param(
    [string]$RedisHome = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\tools\redis'),
    [int]$Port = 6379
)

$ErrorActionPreference = 'Stop'

if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
    Write-Host "Redis is already running on 127.0.0.1:$Port."
    exit 0
}

$server = Join-Path $RedisHome 'redis-server.exe'
if (-not (Test-Path -LiteralPath $server)) {
    throw "Redis not found: $server. Install Redis or pass -RedisHome <dir containing redis-server.exe>."
}

# Development mode: disable RDB/AOF so no persistence files are written into the project directory.
Start-Process -FilePath $server `
    -ArgumentList '--port', $Port, '--save', '""', '--appendonly', 'no' `
    -WindowStyle Hidden

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
        Write-Host "Redis started on 127.0.0.1:$Port."
        exit 0
    }
}

throw "Redis failed to start on port $Port. Check whether the port is in use."
