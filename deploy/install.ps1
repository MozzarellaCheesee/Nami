#Requires -Version 5.1
<#
.SYNOPSIS
    Скрипт установки Nami Music Server для Windows Server и Windows Desktop.
.DESCRIPTION
    Загружает последний релиз nami-server из GitHub, настраивает директорию установки,
    добавляет правило в Брандмауэр Windows (порт 4533), создаёт ярлык и фоновую задачу
    автозапуска, запускает сервер и открывает веб-мастер первичной настройки.
.PARAMETER Repo
    GitHub-репозиторий в формате "Owner/Repo". По умолчанию: MozzarellaCheesee/Nami.
.PARAMETER InstallDir
    Путь установки. По умолчанию: $env:ProgramData\Nami (или $env:LOCALAPPDATA\Nami без админ-прав).
.PARAMETER Port
    Порт сервера (по умолчанию: 4533).
.PARAMETER SkipBrowser
    Не открывать веб-браузер автоматически после завершения установки.
.EXAMPLE
    irm https://raw.githubusercontent.com/MozzarellaCheesee/Nami/main/deploy/install.ps1 | iex
#>

[CmdletBinding()]
param(
    [string]$Repo = "MozzarellaCheesee/Nami",
    [string]$InstallDir = "$env:ProgramData\Nami",
    [int]$Port = 4533,
    [switch]$SkipBrowser
)

$ErrorActionPreference = "Stop"

# Очистка и заголовок
Clear-Host
Write-Host @"
  _   _                 _ 
 | \ | | __ _ _ __ ___ (_)
 |  \| |/ _` | '_ ` _ \| |
 | |\  | (_| | | | | | | |
 |_| \_|\__,_|_| |_| |_|_|
  Windows Server Installer
"@ -ForegroundColor Cyan

Write-Host "------------------------------------------------------------" -ForegroundColor DarkGray

# 1. Проверка разрядности ОС
if (-not [Environment]::Is64BitOperatingSystem) {
    Write-Error "Nami Server требует 64-битную операционную систему Windows (x64)."
    exit 1
}

# 2. Проверка прав Администратора
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    if ($MyInvocation.MyCommand.Path) {
        Write-Host "Запрос прав Администратора для настройки Брандмауэра и автозапуска..." -ForegroundColor Yellow
        try {
            Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Path)`""
            exit 0
        } catch {
            Write-Warning "Пользователь отклонил запрос UAC. Продолжаем установку с правами текущего пользователя."
        }
    }
    
    if (-not $isAdmin) {
        $InstallDir = "$env:LOCALAPPDATA\Nami"
        Write-Warning "Установка будет выполнена локально в: $InstallDir"
        Write-Warning "Правило брандмауэра может потребовать ручного добавления."
    }
}

Write-Host "[1/6] Подготовка рабочей директории..." -ForegroundColor Cyan
if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}
Write-Host "      Папка установки: $InstallDir" -ForegroundColor Gray

$versionFile = Join-Path $InstallDir "version.txt"
$binPath = Join-Path $InstallDir "nami-server.exe"
$currentVersion = $null
if (Test-Path $binPath) {
    try { $currentVersion = ((& $binPath --version 2>$null) -split '\s+')[-1].TrimStart('v') } catch {}
}
if (-not $currentVersion -and (Test-Path $versionFile)) {
    $currentVersion = (Get-Content $versionFile -Raw).Trim().TrimStart('v')
}
if (-not $currentVersion) {
    try {
        $currentVersion = (Invoke-RestMethod -Uri "http://127.0.0.1`:$Port/api/health" -TimeoutSec 2).version
    } catch {}
}
$earlyTargetVersion = $null
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $earlyRelease = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ "User-Agent" = "Nami-Windows-Installer"; "Accept" = "application/vnd.github+json" }
    $earlyTargetVersion = $earlyRelease.tag_name.TrimStart('v')
} catch {}
if ($currentVersion -and $earlyTargetVersion -and $currentVersion.TrimStart('v') -eq $earlyTargetVersion) {
    Write-Host "      ✓ Обновления нет: у вас уже установлена последняя версия $currentVersion." -ForegroundColor Green
    return
}
if ($currentVersion -and $earlyTargetVersion) {
    Write-Host "      Доступно обновление: $currentVersion → $earlyTargetVersion" -ForegroundColor Cyan
}

# 3. Проверка и автоматическая установка FFmpeg
Write-Host "[2/6] Проверка медиа-библиотеки FFmpeg..." -ForegroundColor Cyan
$ffmpegCmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($ffmpegCmd) {
    Write-Host "      ✓ FFmpeg обнаружен в системе: $($ffmpegCmd.Source)" -ForegroundColor Green
} else {
    Write-Host "      FFmpeg не найден. Попытка автоматической установки через winget..." -ForegroundColor Yellow
    $wingetCmd = Get-Command winget -ErrorAction SilentlyContinue
    $installed = $false
    if ($wingetCmd) {
        try {
            Start-Process winget -ArgumentList "install -e --id Gyan.FFmpeg --accept-source-agreements --accept-package-agreements --silent" -Wait -NoNewWindow
            $ffmpegCmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
            if ($ffmpegCmd) {
                Write-Host "      ✓ FFmpeg успешно установлен в систему: $($ffmpegCmd.Source)" -ForegroundColor Green
                $installed = $true
            }
        } catch {
            Write-Warning "Не удалось автоматически установить FFmpeg через winget."
        }
    }
    if (-not $installed) {
        Write-Host "      ⚠️  FFmpeg не установлен. Для включения транскодинга на лету выполните:" -ForegroundColor DarkYellow
        Write-Host "           winget install Gyan.FFmpeg" -ForegroundColor White
    }
}

# 4. Скачивание или сборка бинарника
Write-Host "[3/6] Получение бинарника Nami Server..." -ForegroundColor Cyan
$downloadSucceeded = $false

$assetZip = "nami-server-windows-x86_64.zip"
$assetExe = "nami-server-windows-x86_64.exe"

try {
    Write-Host "      Запрос последнего релиза из GitHub ($Repo)..." -ForegroundColor Gray
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    
    $apiUrl = "https://api.github.com/repos/$Repo/releases"
    $headers = @{ "User-Agent" = "Nami-Windows-Installer"; "Accept" = "application/vnd.github+json" }
    $releases = Invoke-RestMethod -Uri $apiUrl -Headers $headers -ErrorAction SilentlyContinue
    
    $downloadUrl = $null
    $targetVersion = $null
    if ($releases) {
        foreach ($rel in $releases) {
            if ($rel.assets) {
                $zipAsset = $rel.assets | Where-Object { $_.name -like "*windows-x86_64*.zip" } | Select-Object -First 1
                if ($zipAsset) {
                    $downloadUrl = $zipAsset.browser_download_url
                    $targetVersion = $rel.tag_name.TrimStart('v')
                    $isZip = $true
                    break
                }
                $exeAsset = $rel.assets | Where-Object { $_.name -like "*windows-x86_64*.exe" } | Select-Object -First 1
                if ($exeAsset) {
                    $downloadUrl = $exeAsset.browser_download_url
                    $targetVersion = $rel.tag_name.TrimStart('v')
                    $isZip = $false
                    break
                }
            }
        }
    }
    
    if (-not $downloadUrl) {
        $downloadUrl = "https://github.com/$Repo/releases/download/v0.1.1-beta.1/$assetZip"
        $targetVersion = "0.1.1-beta.1"
        $isZip = $true
    }

    if ($currentVersion -and $currentVersion.TrimStart('v') -eq $targetVersion) {
        Write-Host "      ✓ Обновления нет: у вас уже установлена последняя версия $currentVersion." -ForegroundColor Green
        return
    }
    if ($currentVersion) {
        Write-Host "      Обновление: $currentVersion → $targetVersion" -ForegroundColor Cyan
    }
    
    Write-Host "      Загрузка с: $downloadUrl" -ForegroundColor Gray
    $tempFile = Join-Path $env:TEMP "nami-server-download.tmp"
    
    Invoke-WebRequest -Uri $downloadUrl -OutFile $tempFile -UseBasicParsing
    
    # Остановим предыдущий запущенный процесс, если он работает
    Get-Process "nami-server" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    
    if ($isZip) {
        Write-Host "      Распаковка архива..." -ForegroundColor Gray
        $tempExtract = Join-Path $env:TEMP "nami-extract-$(Get-Random)"
        Expand-Archive -Path $tempFile -DestinationPath $tempExtract -Force
        
        $foundExe = Get-ChildItem -Path $tempExtract -Filter "nami-server*.exe" -Recurse | Select-Object -First 1
        if ($foundExe) {
            Copy-Item -Path $foundExe.FullName -Destination $binPath -Force
            $downloadSucceeded = $true
        }
        Remove-Item -Path $tempExtract -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Copy-Item -Path $tempFile -Destination $binPath -Force
        $downloadSucceeded = $true
    }
    Remove-Item -Path $tempFile -Force -ErrorAction SilentlyContinue
} catch {
    Write-Warning "      Не удалось скачать предсобранный релиз: $($_.Exception.Message)"
}

# Запасной вариант: локальная компиляция, если есть cargo
if (-not $downloadSucceeded -or -not (Test-Path $binPath)) {
    $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
    if ($cargoCmd) {
        Write-Host "      Обнаружен компилятор Rust (cargo). Сборка nami-server из исходников..." -ForegroundColor Yellow
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
        $repoRoot = Split-Path -Parent $scriptDir
        
        if (Test-Path (Join-Path $repoRoot "server\Cargo.toml")) {
            Push-Location (Join-Path $repoRoot "server")
            cargo build --release
            Pop-Location
            $builtExe = Join-Path $repoRoot "server\target\release\nami-server.exe"
            if (Test-Path $builtExe) {
                Copy-Item -Path $builtExe -Destination $binPath -Force
                $downloadSucceeded = $true
            }
        }
    }
}

if (-not (Test-Path $binPath)) {
    Write-Error "Не удалось получить или собрать nami-server.exe. Проверьте интернет-соединение или наличие релизов на GitHub."
    exit 1
}
Write-Host "      ✓ Исполняемый файл готов: $binPath" -ForegroundColor Green
if ($targetVersion) {
    Set-Content -LiteralPath $versionFile -Value $targetVersion -Encoding ASCII
    if ($currentVersion) {
        Write-Host "      ✓ Сервер обновлён: $currentVersion → $targetVersion" -ForegroundColor Green
    } else {
        Write-Host "      ✓ Установлена версия $targetVersion" -ForegroundColor Green
    }
}

# 5. Настройка Брандмауэра Windows
Write-Host "[4/6] Настройка сетевого доступа (Брандмауэр Windows)..." -ForegroundColor Cyan
if ($isAdmin) {
    try {
        $ruleName = "Nami Music Server (TCP $Port)"
        $existingRule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        if (-not $existingRule) {
            New-NetFirewallRule -DisplayName $ruleName `
                -Description "Разрешить входящие подключения к Hi-Res аудиосерверу Nami" `
                -Direction Inbound `
                -LocalPort $Port `
                -Protocol TCP `
                -Action Allow `
                -Profile Any `
                -Program $binPath | Out-Null
            Write-Host "      ✓ Правило брандмауэра успешно создано для порта $Port" -ForegroundColor Green
        } else {
            Write-Host "      ✓ Правило брандмауэра уже существует" -ForegroundColor Green
        }
    } catch {
        Write-Warning "      Не удалось настроить брандмауэр: $($_.Exception.Message)"
    }
} else {
    Write-Warning "      Пропуск настройки брандмауэра (требуются права Администратора)."
    Write-Warning "      Если смартфон не сможет подключиться, добавьте порт $Port TCP в исключения Брандмауэра."
}

# 6. Ярлык и фоновая задача автозапуска
Write-Host "[5/6] Создание ярлыков и задачи автозапуска..." -ForegroundColor Cyan
try {
    $wshShell = New-Object -ComObject WScript.Shell
    
    # Ярлык в меню Пуск
    $programsPath = if ($isAdmin) { [Environment]::GetFolderPath("CommonPrograms") } else { [Environment]::GetFolderPath("Programs") }
    $shortcutPath = Join-Path $programsPath "Nami Server.lnk"
    $shortcut = $wshShell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $binPath
    $shortcut.WorkingDirectory = $InstallDir
    $shortcut.Description = "Nami Hi-Res Music Server"
    $shortcut.Save()
    Write-Host "      ✓ Создан ярлык в меню Пуск" -ForegroundColor Green

    # Планировщик задач Windows (автозапуск при входе пользователя)
    if ($isAdmin) {
        $taskName = "NamiServer"
        $action = New-ScheduledTaskAction -Execute $binPath -WorkingDirectory $InstallDir
        $trigger = New-ScheduledTaskTrigger -AtLogOn
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
        
        $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Highest
        
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
        Write-Host "      ✓ Зарегистрирована фоновая задача автозапуска '$taskName'" -ForegroundColor Green
    }
} catch {
    Write-Warning "      Не удалось создать ярлык или задачу: $($_.Exception.Message)"
}

# 7. Запуск сервера и открытие мастера настройки
Write-Host "[6/6] Запуск Nami Server..." -ForegroundColor Cyan
$running = Get-Process "nami-server" -ErrorAction SilentlyContinue
if (-not $running) {
    Start-Process -FilePath $binPath -WorkingDirectory $InstallDir
    Write-Host "      Сервер запущен в фоновом режиме." -ForegroundColor Green
} else {
    Write-Host "      Сервер уже запущен (PID: $($running.Id))." -ForegroundColor Green
}

# Определение локального IP-адреса для подсказки подключения
$localIP = (Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias "Wi-Fi*", "Ethernet*", "Беспроводная*", "Подключение*" -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object -ExpandProperty IPAddress -First 1)

if (-not $localIP) { $localIP = "127.0.0.1" }

Start-Sleep -Seconds 2

$setupUrl = "http://localhost:$Port/setup"
$lanSetupUrl = "http://$localIP`:$Port/setup"

if (-not $SkipBrowser) {
    Write-Host "      Открытие мастера настройки в браузере..." -ForegroundColor Gray
    Start-Process $setupUrl
}

Write-Host @"

======================================================================
  🎉 Nami Server успешно установлен и готов к работе!
======================================================================

  ШАГ 1. Первичная настройка (открыта в браузере):
         👉 $setupUrl
         (для настройки с других устройств: $lanSetupUrl)

  ШАГ 2. В мастере укажите:
         • Папку с музыкальной коллекцией (например: D:\Music);
         • Логин и пароль администратора (от 8 символов);
         • Нажмите «Сохранить конфигурацию».

  ШАГ 3. Перезапустите сервер (через Диспетчер задач или ярлык в Пуске).

  ШАГ 4. Сопряжение с Android-клиентом:
         • Снова откройте $lanSetupUrl
         • Отсканируйте отобразившийся QR-код камерой в приложении Nami:
           «Настройки» → «Подключить сервер» → «Сканировать QR».

  Рабочая директория: $InstallDir
======================================================================

"@ -ForegroundColor Green
