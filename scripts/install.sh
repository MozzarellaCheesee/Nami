#!/bin/sh
# Установщик Nami одной командой: curl -fsSL https://get.nami.app | sh

set -e

echo "=== Установка Nami-сервера ==="
echo

# Определение ОС
OS=$(uname -s)
ARCH=$(uname -m)

case "$OS" in
    Linux)
        PLATFORM="linux"
        ;;
    Darwin)
        PLATFORM="macos"
        ;;
    *)
        echo "Ошибка: неподдерживаемая ОС: $OS"
        exit 1
        ;;
esac

case "$ARCH" in
    x86_64)
        ARCH_SUFFIX="x86_64"
        ;;
    aarch64|arm64)
        ARCH_SUFFIX="aarch64"
        ;;
    *)
        echo "Ошибка: неподдерживаемая архитектура: $ARCH"
        exit 1
        ;;
esac

BINARY="nami-$PLATFORM-$ARCH_SUFFIX"
GITHUB_REPO="your-org/nami"
LATEST_URL="https://api.github.com/repos/$GITHUB_REPO/releases/latest"

echo "Платформа: $PLATFORM $ARCH_SUFFIX"

# Скачивание последней версии
echo "Получение последнего релиза..."
RELEASE_JSON=$(curl -fsSL "$LATEST_URL")
DOWNLOAD_URL=$(echo "$RELEASE_JSON" | grep "browser_download_url.*$BINARY" | cut -d '"' -f 4)

if [ -z "$DOWNLOAD_URL" ]; then
    echo "Ошибка: не найден бинарник для $PLATFORM $ARCH_SUFFIX"
    exit 1
fi

VERSION=$(echo "$RELEASE_JSON" | grep '"tag_name"' | cut -d '"' -f 4)
echo "Версия: $VERSION"

# Установка бинарника
INSTALL_DIR="/usr/local/bin"
if [ ! -w "$INSTALL_DIR" ]; then
    echo "Требуются права root для установки в $INSTALL_DIR"
    SUDO="sudo"
else
    SUDO=""
fi

echo "Скачивание бинарника..."
TMP_FILE="/tmp/nami-$VERSION"
curl -fsSL "$DOWNLOAD_URL" -o "$TMP_FILE"
chmod +x "$TMP_FILE"

echo "Установка в $INSTALL_DIR..."
$SUDO mv "$TMP_FILE" "$INSTALL_DIR/nami"

# Создание конфигурации
DATA_DIR="$HOME/.local/share/nami"
CONFIG_DIR="$HOME/.config/nami"
mkdir -p "$DATA_DIR" "$CONFIG_DIR"

CONFIG_FILE="$CONFIG_DIR/config.toml"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Создание конфигурации: $CONFIG_FILE"
    cat > "$CONFIG_FILE" <<EOF
# Порт сервера
port = 3030

# Включить TLS (самоподписанный сертификат)
tls = true

# Путь к базе данных
db_path = "$DATA_DIR/nami.db"

# Музыкальные папки для сканирования
music_dirs = ["$HOME/Music"]

# Папка для кеша транскодов
cache_dir = "$DATA_DIR/cache"

# Лимит кеша транскодов (МБ)
transcode_cache_mb = 1024

# Включить автосканирование при изменении файлов
watch = true

# TTL удалённых записей (дни)
tombstone_ttl_days = 30

# Ключ DeepL API (для перевода лирики, опционально)
deepl_api_key = ""

# Язык перевода лирики по умолчанию
lyrics_target_lang = "RU"
EOF
    echo "Отредактируйте $CONFIG_FILE под свои нужды"
fi

# Определение метода установки
USE_SYSTEMD=false
USE_DOCKER=false

if command -v systemctl >/dev/null 2>&1 && [ "$PLATFORM" = "linux" ]; then
    USE_SYSTEMD=true
elif command -v docker >/dev/null 2>&1; then
    USE_DOCKER=true
fi

# Установка как systemd service (Linux)
if [ "$USE_SYSTEMD" = true ]; then
    echo
    echo "Установка systemd unit..."
    SERVICE_FILE="/etc/systemd/system/nami.service"
    $SUDO tee "$SERVICE_FILE" > /dev/null <<EOF
[Unit]
Description=Nami Music Server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$CONFIG_DIR
ExecStart=$INSTALL_DIR/nami
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

    $SUDO systemctl daemon-reload
    $SUDO systemctl enable nami
    $SUDO systemctl start nami

    echo
    echo "✓ Nami установлен и запущен через systemd"
    echo
    nami status

# Установка через Docker
elif [ "$USE_DOCKER" = true ]; then
    echo
    echo "Примечание: обнаружен Docker, но бинарник установлен локально."
    echo "Для запуска через Docker используйте docker-compose из репозитория."
    echo
    echo "Запуск standalone..."
    cd "$CONFIG_DIR"
    nohup nami > "$DATA_DIR/nami.log" 2>&1 &
    echo "✓ Nami запущен в фоне. Логи: $DATA_DIR/nami.log"

# Установка через launchd (macOS)
elif [ "$PLATFORM" = "macos" ]; then
    echo
    echo "Установка launchd plist..."
    PLIST_FILE="$HOME/Library/LaunchAgents/app.nami.server.plist"
    mkdir -p "$HOME/Library/LaunchAgents"
    cat > "$PLIST_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>app.nami.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>$INSTALL_DIR/nami</string>
    </array>
    <key>WorkingDirectory</key>
    <string>$CONFIG_DIR</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$DATA_DIR/nami.log</string>
    <key>StandardErrorPath</key>
    <string>$DATA_DIR/nami.log</string>
</dict>
</plist>
EOF

    launchctl load "$PLIST_FILE"
    echo "✓ Nami установлен и запущен через launchd"
    echo
    nami status

# Standalone запуск
else
    echo
    echo "Запуск standalone..."
    cd "$CONFIG_DIR"
    nohup nami > "$DATA_DIR/nami.log" 2>&1 &
    echo "✓ Nami запущен в фоне. Логи: $DATA_DIR/nami.log"
fi

echo
echo "=== Установка завершена ==="
echo
echo "Сервер доступен по адресу: https://localhost:3030"
echo "Мастер настройки: https://localhost:3030/setup"
echo
echo "Управление:"
echo "  nami status    - статус сервера"
echo "  nami logs      - просмотр логов"
echo "  nami doctor    - диагностика"
echo
