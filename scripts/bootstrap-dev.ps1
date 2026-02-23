[CmdletBinding()]
param(
    [switch]$SkipJava,
    [switch]$SkipPython,
    [switch]$SkipNode
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    Write-Host "[bootstrap-dev] $Message"
}

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Comando obrigatorio ausente: $Name"
    }
}

function Invoke-Python {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$PythonArgs
    )

    if (Get-Command python -ErrorAction SilentlyContinue) {
        & python @PythonArgs
        return
    }

    if (Get-Command py -ErrorAction SilentlyContinue) {
        & py -3 @PythonArgs
        return
    }

    throw "Comando obrigatorio ausente: py ou python"
}

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if (-not $SkipJava) {
    Require-Command mvn
    Write-Log "Baixando dependencias Maven (inclui validacao de Java 21.x)..."
    Push-Location $RootDir
    try {
        & mvn -B -DskipTests validate dependency:go-offline
    } finally {
        Pop-Location
    }
}

if (-not $SkipPython) {
    $RequirementsPath = Join-Path $RootDir "solver\requirements.txt"
    if (-not (Test-Path $RequirementsPath)) {
        throw "Arquivo obrigatorio ausente: solver/requirements.txt"
    }

    $VenvDir = Join-Path $RootDir "solver\.venv"
    Write-Log "Criando/atualizando virtualenv Python em solver/.venv..."
    Invoke-Python -PythonArgs @("-m", "venv", $VenvDir)

    $VenvPython = Join-Path $VenvDir "Scripts\python.exe"
    & $VenvPython -m pip install --upgrade pip
    & $VenvPython -m pip install -r $RequirementsPath
}

if (-not $SkipNode) {
    Require-Command npm

    $UiDir = Join-Path $RootDir "produto-ui\prototipo"
    $LockFile = Join-Path $UiDir "package-lock.json"
    if (-not (Test-Path $LockFile)) {
        throw "Arquivo obrigatorio ausente: produto-ui/prototipo/package-lock.json"
    }

    Write-Log "Instalando dependencias Node (npm ci) em produto-ui/prototipo..."
    Push-Location $UiDir
    try {
        & npm ci
    } finally {
        Pop-Location
    }
}

Write-Log "Bootstrap concluido."
