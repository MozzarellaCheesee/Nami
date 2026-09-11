#!/usr/bin/env bash
# ==============================================================================
# 🌊 Nami Music Server — Linux Installation Script
# https://github.com/MozzarellaCheesee/Nami
#
# Использование:
#   curl -fsSL https://raw.githubusercontent.com/MozzarellaCheesee/Nami/main/deploy/install.sh | bash
#   или локально:
#   bash deploy/install.sh
# ==============================================================================

set -euo pipefail

GITHUB_REPO="${GITHUB_REPO:-MozzarellaCheesee/Nami}"
INSTALL_DIR="/usr/local/bin"
DATA_DIR="/var/lib/nami"
SERVICE_NAME="nami"
SYSTEMD_UNIT="/etc/systemd/system/${SERVICE_NAME}.service"
PORT=4533

# Цвета для терминала
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log_info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

echo -e "${BOLD}${CYAN}"
cat << "EOF"
  _   _                 _ 
 | \ | | __ _ _ __ ___ (_)
 |  \| |/ _` | '_ ` _ \| |
 | |\  | (_| | | | | | | |
 |_| \_|\__,_|_| |_| |_|_|
  Hi-Res Music Server Installer
EOF
echo -e "${NC}"

# 1. Проверка операционной системы
OS="$(uname -s)"
if [ "$OS" != "Linux" ]; then
    log_error "Данный скрипт предназначен только для Linux систем с systemd (обнаружена ОС: $OS)."
    exit 1
fi

# 2. Проверка наличия systemd
if ! command -v systemctl >/dev/null 2>&1; then
    log_error "systemctl не найден. Этот скрипт требует systemd для управления службой."
    exit 1
fi

# 3. Определение архитектуры
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64)
        TARGET_ARCH="x86_64"
        ;;
    aarch64|arm64)
        TARGET_ARCH="aarch64"
        ;;
    *)
        log_error "Неподдерживаемая архитектура процессора: $ARCH. Поддерживаются x86_64 и aarch64."
        exit 1
        ;;
esac
log_info "Определена платформа: Linux ${TARGET_ARCH}"

# 4. Проверка прав root / sudo
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    if command -v sudo >/dev/null 2>&1; then
        SUDO="sudo"
        log_info "Запуск с правами sudo..."
    else
        log_error "Для установки требуются права root или утилита sudo."
        exit 1
    fi
fi

# 5. Проверка наличия ffmpeg
echo
log_info "Проверка мультимедиа-компонентов..."
if command -v ffmpeg >/dev/null 2>&1; then
    log_ok "ffmpeg найден: $(ffmpeg -version | head -n1)"
else
    log_warn "ffmpeg не найден в системе!"
    echo -e "${YELLOW}   Сервер будет работать в режиме passthrough (прямая отдача FLAC/MP3)."
    echo -e "   Для мобильного транскодинга на лету (Opus/AAC) рекомендуется установить ffmpeg:"
    echo -e "     Debian/Ubuntu: ${BOLD}sudo apt update && sudo apt install -y ffmpeg${NC}"
    echo -e "     Arch Linux:    ${BOLD}sudo pacman -S ffmpeg${NC}"
    echo -e "     Fedora/RHEL:   ${BOLD}sudo dnf install ffmpeg${NC}"
    echo
fi

# 6. Получение бинарника (GitHub Release или fallback на cargo)
TMP_DIR="$(mktemp -d /tmp/nami-install-XXXXXX)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

BINARY_FOUND=false
ASSET_NAME="nami-server-linux-${TARGET_ARCH}.tar.gz"
DOWNLOAD_SUCCESS=false

log_info "Поиск релиза в репозитории ${GITHUB_REPO}..."

# Пытаемся получить URL последнего релиза через GitHub API
LATEST_API_URL="https://api.github.com/repos/${GITHUB_REPO}/releases/latest"
RELEASE_JSON="$(curl -fsSL "$LATEST_API_URL" 2>/dev/null || true)"

DOWNLOAD_URL=""
if [ -n "$RELEASE_JSON" ]; then
    DOWNLOAD_URL="$(echo "$RELEASE_JSON" | grep -o "https://[^\"]*${ASSET_NAME}" | head -n1 || true)"
fi

if [ -z "$DOWNLOAD_URL" ]; then
    DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/${ASSET_NAME}"
fi

log_info "Попытка скачивания бинарного архива: ${ASSET_NAME}..."
if curl -fsSL -L "$DOWNLOAD_URL" -o "${TMP_DIR}/${ASSET_NAME}" 2>/dev/null; then
    log_info "Распаковка архива..."
    if tar -xzf "${TMP_DIR}/${ASSET_NAME}" -C "$TMP_DIR" 2>/dev/null; then
        if [ -f "${TMP_DIR}/nami-server" ]; then
            BINARY_FOUND=true
            DOWNLOAD_SUCCESS=true
            log_ok "Бинарный файл nami-server успешно получен из релиза."
        fi
    fi
fi

# Запасной вариант: сборка из исходников, если доступен cargo
if [ "$BINARY_FOUND" = false ]; then
    log_warn "Не удалось скачать готовый бинарник из GitHub Release."
    if command -v cargo >/dev/null 2>&1; then
        log_info "Обнаружен Rust toolchain (cargo). Попытка сборки из исходников..."
        
        # Если скрипт запущен внутри клонированного репозитория Nami
        SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
        
        if [ -f "${REPO_ROOT}/server/Cargo.toml" ]; then
            log_info "Найдена локальная директория сервера: ${REPO_ROOT}/server"
            (cd "${REPO_ROOT}/server" && cargo build --release)
            cp "${REPO_ROOT}/server/target/release/nami-server" "${TMP_DIR}/nami-server"
            BINARY_FOUND=true
        else
            log_info "Клонирование репозитория для компиляции..."
            BUILD_DIR="${TMP_DIR}/repo"
            git clone --depth 1 "https://github.com/${GITHUB_REPO}.git" "$BUILD_DIR"
            (cd "${BUILD_DIR}/server" && cargo build --release)
            cp "${BUILD_DIR}/server/target/release/nami-server" "${TMP_DIR}/nami-server"
            BINARY_FOUND=true
        fi
        log_ok "Сборка из исходников успешно завершена."
    else
        log_error "Не удалось скачать релиз и не найден Rust toolchain (cargo) для сборки."
        log_error "Проверьте подключение к сети или установите cargo (https://rustup.rs)."
        exit 1
    fi
fi

# 7. Установка исполняемого файла
log_info "Установка nami-server в ${INSTALL_DIR}..."
$SUDO install -m 755 "${TMP_DIR}/nami-server" "${INSTALL_DIR}/nami-server"
$SUDO ln -sf "${INSTALL_DIR}/nami-server" "${INSTALL_DIR}/nami"
log_ok "Установлен: ${INSTALL_DIR}/nami-server (симлинк ${INSTALL_DIR}/nami)"

# 8. Создание системного пользователя nami
if ! id -u nami >/dev/null 2>&1; then
    log_info "Создание системного пользователя 'nami'..."
    $SUDO useradd -r -s /usr/sbin/nologin -d "$DATA_DIR" -M nami 2>/dev/null || \
    $SUDO useradd -r -s /bin/false -d "$DATA_DIR" -M nami 2>/dev/null || \
    $SUDO adduser --system --no-create-home --group --shell /usr/sbin/nologin nami 2>/dev/null || true
    log_ok "Пользователь 'nami' создан."
else
    log_info "Системный пользователь 'nami' уже существует."
fi

# 9. Создание рабочей директории
log_info "Настройка директории данных ${DATA_DIR}..."
$SUDO mkdir -p "$DATA_DIR"
$SUDO chown -R nami:nami "$DATA_DIR"
$SUDO chmod 750 "$DATA_DIR"

# 10. Создание systemd unit
log_info "Создание systemd службы: ${SYSTEMD_UNIT}..."
cat << EOF | $SUDO tee "$SYSTEMD_UNIT" > /dev/null
[Unit]
Description=Nami Hi-Res Music Server
Documentation=https://github.com/${GITHUB_REPO}
After=network.target network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nami
Group=nami
WorkingDirectory=${DATA_DIR}
ExecStart=${INSTALL_DIR}/nami-server
Restart=always
RestartSec=3s
LimitNOFILE=65536
AmbientCapabilities=CAP_NET_BIND_SERVICE
StandardOutput=journal
StandardError=journal

# Sandboxing & Permissions
ProtectSystem=full
ProtectHome=read-only
ReadWritePaths=${DATA_DIR}
PrivateTmp=true
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF

# 11. Активация и запуск службы
log_info "Перезагрузка systemd и запуск службы ${SERVICE_NAME}..."
$SUDO systemctl daemon-reload
$SUDO systemctl enable "${SERVICE_NAME}.service"
$SUDO systemctl restart "${SERVICE_NAME}.service"

# Небольшая пауза для запуска порта
sleep 2

# 12. Определение IP адреса хоста
PRIMARY_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')"
if [ -z "$PRIMARY_IP" ]; then
    PRIMARY_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
if [ -z "$PRIMARY_IP" ]; then
    PRIMARY_IP="127.0.0.1"
fi

# 13. Итоговое сообщение
echo
echo -e "${BOLD}${GREEN}======================================================================${NC}"
echo -e "${BOLD}${GREEN}  🎉 Nami Server успешно установлен и запущен!${NC}"
echo -e "${BOLD}${GREEN}======================================================================${NC}"
echo
echo -e "  ${BOLD}ШАГ 1. Первичная настройка в браузере:${NC}"
echo -e "         👉 ${BOLD}${CYAN}http://${PRIMARY_IP}:${PORT}/setup${NC}"
echo -e "         (или локально: ${CYAN}http://localhost:${PORT}/setup${NC})"
echo
echo -e "  ${BOLD}ШАГ 2. В мастере укажите:${NC}"
echo -e "         • Папку с вашей музыкальной коллекцией (например: /home/music);"
echo -e "         • Логин и пароль администратора (от 8 символов);"
echo -e "         • Нажмите «Сохранить конфигурацию»."
echo
echo -e "  ${BOLD}ШАГ 3. Примените настройки (перезапуск):${NC}"
echo -e "         ${BOLD}sudo systemctl restart nami${NC}"
echo
echo -e "  ${BOLD}ШАГ 4. Сопряжение с Android-клиентом:${NC}"
echo -e "         • Снова откройте ${CYAN}http://${PRIMARY_IP}:${PORT}/setup${NC}"
echo -e "         • На странице отобразится ${BOLD}QR-код${NC} и 8-значный код."
echo -e "         • В приложении Nami на смартфоне откройте:"
echo -e "           ${BOLD}Настройки → Подключить сервер → Сканировать QR${NC}"
echo
echo -e "${BOLD}Полезные команды управления:${NC}"
echo -e "  ${CYAN}nami status${NC}           - Проверка статуса сервера и здоровья API"
echo -e "  ${CYAN}nami logs${NC}             - Просмотр журнала последних логов"
echo -e "  ${CYAN}nami doctor${NC}           - Комплексная диагностика (порты, ffmpeg, БД)"
echo -e "  ${CYAN}sudo systemctl status nami${NC} - Статус службы systemd"
echo -e "${BOLD}${GREEN}======================================================================${NC}"
echo
