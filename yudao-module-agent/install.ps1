#!/usr/bin/env pwsh
<#
.SYNOPSIS
    my-agents 一键依赖安装脚本 (类似 npm install)
.DESCRIPTION
    自动检测 Python 环境和包管理器，安装全部项目依赖。
    支持三种模式: core（核心依赖）、full（全功能）、dev（开发环境）。
.PARAMETER Mode
    安装模式: core / full / dev（默认 full）
.PARAMETER Force
    强制重新安装
.EXAMPLE
    .\install.ps1              # 安装全部依赖
    .\install.ps1 -Mode core   # 仅安装核心依赖
    .\install.ps1 -Mode dev    # 安装开发依赖
    .\install.ps1 -Force       # 强制重新安装
.NOTES
    等同于 Node.js 的 npm install
#>

param(
    [ValidateSet("core", "full", "dev")]
    [string]$Mode = "full",

    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# ========== 彩色输出 ==========
function Write-Step {
    param([string]$Text)
    Write-Host "`n==> " -NoNewline -ForegroundColor Cyan
    Write-Host $Text -ForegroundColor White
}

function Write-OK {
    param([string]$Text)
    Write-Host "  [OK] " -NoNewline -ForegroundColor Green
    Write-Host $Text
}

function Write-Warn {
    param([string]$Text)
    Write-Host "  [WARN] " -NoNewline -ForegroundColor Yellow
    Write-Host $Text
}

function Write-Err {
    param([string]$Text)
    Write-Host "  [ERROR] " -NoNewline -ForegroundColor Red
    Write-Host $Text
}

# ========== Banner ==========
Write-Host @"

  ╔══════════════════════════════════════════════╗
  ║        my-agents v0.2.0  依赖安装器          ║
  ╚══════════════════════════════════════════════╝

"@ -ForegroundColor Magenta

# ========== 1. 检查 Python ==========
Write-Step "检查 Python 环境..."

$pythonCmd = $null
foreach ($cmd in @("python3", "python")) {
    try {
        $v = & $cmd --version 2>&1
        if ($LASTEXITCODE -eq 0) {
            $pythonCmd = $cmd
            break
        }
    } catch { }
}

if (-not $pythonCmd) {
    Write-Err "未找到 Python，请先安装 Python >= 3.12"
    Write-Host "  下载: https://www.python.org/downloads/" -ForegroundColor Gray
    exit 1
}

$pyVersion = (& $pythonCmd --version 2>&1) -replace "Python ", ""
Write-OK "Python $pyVersion"

# 检查版本 >= 3.12
$verMatch = [regex]::Match($pyVersion, '(\d+)\.(\d+)')
if ($verMatch.Success) {
    $major = [int]$verMatch.Groups[1].Value
    $minor = [int]$verMatch.Groups[2].Value
    if ($major -lt 3 -or ($major -eq 3 -and $minor -lt 12)) {
        Write-Err "需要 Python >= 3.12，当前版本 $pyVersion"
        exit 1
    }
}

# ========== 2. 检测包管理器 ==========
Write-Step "检测包管理器..."

$useUv = $false
$pkgManager = "pip"

try {
    $uvVersion = & uv --version 2>&1
    if ($LASTEXITCODE -eq 0) {
        $useUv = $true
        $pkgManager = "uv"
        Write-OK "uv $uvVersion (推荐)"
    }
} catch { }

if (-not $useUv) {
    Write-OK "pip (使用 pip 安装)"
    Write-Warn "建议安装 uv 以获得更快速度: pip install uv"
}

# ========== 3. 检查虚拟环境 ==========
Write-Step "检查虚拟环境..."

$inVenv = $false
if ($env:VIRTUAL_ENV) {
    $inVenv = $true
    Write-OK "已在虚拟环境中: $env:VIRTUAL_ENV"
} elseif ($env:CONDA_PREFIX) {
    $inVenv = $true
    Write-OK "已在 Conda 环境中: $env:CONDA_PREFIX"
} else {
    Write-Warn "未激活虚拟环境，建议创建虚拟环境后安装"
    Write-Host "  创建: python -m venv .venv" -ForegroundColor Gray
    Write-Host "  激活: .\.venv\Scripts\Activate.ps1" -ForegroundColor Gray
    Write-Host ""
    $choice = Read-Host "  是否继续在全局环境安装？(y/N)"
    if ($choice -notmatch '^[yY]') {
        Write-Host "已取消。请先创建虚拟环境。" -ForegroundColor Yellow
        exit 0
    }
}

# ========== 4. 构建安装命令 ==========
Write-Step "准备安装 (模式: $Mode)..."

if ($Force) {
    Write-Host "  --force 模式: 将强制重新安装" -ForegroundColor Gray
}

function Invoke-Install {
    param([string[]]$Packages)

    foreach ($pkg in $Packages) {
        Write-Host "  安装: $pkg" -ForegroundColor Gray
        if ($useUv) {
            if ($Force) {
                uv pip install --reinstall $pkg
            } else {
                uv pip install $pkg
            }
        } else {
            if ($Force) {
                & $pythonCmd -m pip install --force-reinstall $pkg
            } else {
                & $pythonCmd -m pip install $pkg
            }
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Err "安装失败: $pkg"
            exit 1
        }
    }
}

# 先安装项目本身（可编辑模式）
Write-Host "`n  [1/2] 安装项目核心依赖..." -ForegroundColor Gray

if ($useUv) {
    if ($Force) {
        uv sync --reinstall
    } else {
        uv sync
    }
} else {
    $installArgs = @("-m", "pip", "install", "-e", ".")
    if ($Force) {
        $installArgs += "--force-reinstall"
    }
    & $pythonCmd @installArgs
}
if ($LASTEXITCODE -ne 0) {
    Write-Err "核心依赖安装失败"
    exit 1
}
Write-OK "核心依赖安装完成"

# 安装可选依赖
if ($Mode -eq "full" -or $Mode -eq "dev") {
    Write-Host "`n  [2/2] 安装可选依赖..." -ForegroundColor Gray

    $optionalGroups = @(
        "search",
        "documents",
        "retrieval",
        "redis",
        "postgres",
        "classify",
        "evaluate",
        "rag"
    )

    if ($useUv) {
        foreach ($group in $optionalGroups) {
            Write-Host "    安装组: [$group]" -ForegroundColor Gray
            $pkgName = "my-agents[$group]"
            if ($Force) {
                uv pip install --reinstall $pkgName
            } else {
                uv pip install $pkgName
            }
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "可选组 [$group] 安装失败，跳过（不影响核心功能）"
            }
        }
    } else {
        $extras = $optionalGroups -join ","
        $installArgs = @("-m", "pip", "install", "-e", ".[$extras]")
        if ($Force) {
            $installArgs += "--force-reinstall"
        }
        & $pythonCmd @installArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "部分可选依赖安装失败（不影响核心功能）"
        }
    }
    Write-OK "可选依赖安装完成"
}

# 安装开发依赖
if ($Mode -eq "dev") {
    Write-Host "`n  [3/3] 安装开发依赖..." -ForegroundColor Gray

    if ($useUv) {
        uv sync --group dev
    } else {
        $devDeps = @("httpx", "pytest", "pytest-asyncio")
        foreach ($dep in $devDeps) {
            & $pythonCmd -m pip install $dep
        }
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "部分开发依赖安装失败"
    }
    Write-OK "开发依赖安装完成"
}

# ========== 5. 验证 ==========
Write-Step "验证安装..."

$verifyErrors = 0

# 验证核心包
$corePkgs = @("fastapi", "langchain", "langgraph", "jieba", "duckduckgo_search")
foreach ($pkg in $corePkgs) {
    try {
        $result = & $pythonCmd -c "import $pkg" 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-OK $pkg
        } else {
            Write-Err $pkg
            $verifyErrors++
        }
    } catch {
        Write-Err $pkg
        $verifyErrors++
    }
}

# ========== 6. 结果 ==========
Write-Host ""
if ($verifyErrors -eq 0) {
    Write-Host "  安装完成！所有依赖就绪。" -ForegroundColor Green
    Write-Host "  启动服务: " -NoNewline
    Write-Host "python -m app.main" -ForegroundColor Cyan
    Write-Host "  运行测试: " -NoNewline
    Write-Host "pytest tests/ -v" -ForegroundColor Cyan
} else {
    Write-Host "  安装完成，但有 $verifyErrors 个包验证失败。" -ForegroundColor Yellow
    Write-Host "  请检查上述错误信息。" -ForegroundColor Yellow
}

exit $verifyErrors