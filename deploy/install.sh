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

# Проверяем, является ли запуск обновлением уже установленного сервера
IS_UPDATE=false
if [ -f "${INSTALL_DIR}/nami-server" ] || [ -f "${INSTALL_DIR}/nami" ] || [ -f "${SYSTEMD_UNIT}" ] || [ -d "${DATA_DIR}" ] || (command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null); then
    IS_UPDATE=true
fi

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
if [ "$IS_UPDATE" = true ]; then
    log_ok "Обнаружен ранее установленный Nami Server. Режим: ОБНОВЛЕНИЕ (данные и база сохраняются)."
else
    log_info "Режим: ПЕРВИЧНАЯ УСТАНОВКА Nami Server."
fi

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

# Проверяем версию до установки пакетов, скачивания и перезапуска службы.
EARLY_REQUESTED_VERSION="${1:-${VERSION:-}}"
EARLY_CURRENT_VERSION=""
if [ -x "${INSTALL_DIR}/nami-server" ]; then
    EARLY_CURRENT_VERSION="$(${INSTALL_DIR}/nami-server --version 2>/dev/null | awk '{print $NF}' || true)"
fi
if [ -z "$EARLY_CURRENT_VERSION" ] && [ -f "${DATA_DIR}/version" ]; then
    EARLY_CURRENT_VERSION="$($SUDO cat "${DATA_DIR}/version" 2>/dev/null | tr -d '[:space:]' || true)"
elif [ -z "$EARLY_CURRENT_VERSION" ] && command -v curl >/dev/null 2>&1; then
    HEALTH_JSON="$(curl -kfsS --connect-timeout 2 "https://127.0.0.1:${PORT}/api/health" 2>/dev/null || curl -fsS --connect-timeout 2 "http://127.0.0.1:${PORT}/api/health" 2>/dev/null || true)"
    EARLY_CURRENT_VERSION="$(printf '%s' "$HEALTH_JSON" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"
fi
EARLY_TARGET_VERSION="$EARLY_REQUESTED_VERSION"
if [ -z "$EARLY_TARGET_VERSION" ] && command -v curl >/dev/null 2>&1; then
    EARLY_TARGET_VERSION="$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n1 || true)"
fi
if [ -n "$EARLY_CURRENT_VERSION" ] && [ -n "$EARLY_TARGET_VERSION" ] && [ "${EARLY_CURRENT_VERSION#v}" = "${EARLY_TARGET_VERSION#v}" ]; then
    log_ok "Обновления нет: у вас уже установлена последняя версия ${EARLY_CURRENT_VERSION#v}."
    exit 0
fi


# ==============================================================================
# TUI: выбор режима доступа
# ==============================================================================
# Скрипт обычно запускают через `curl ... | bash`, и тогда stdin занят самим
# скриптом - обычный `read` мгновенно получил бы EOF. Поэтому весь ввод идёт из
# /dev/tty. Само существование файла ничего не гарантирует (в контейнере без
# выделенного терминала открытие падает с ENXIO), поэтому пробуем открыть.
TTY_IN=""
if { : < /dev/tty; } 2>/dev/null; then
    TTY_IN=/dev/tty
fi

# Значения можно задать заранее - тогда вопросов не будет вовсе:
#   NAMI_MODE=domain|lan|proxy  NAMI_DOMAIN=music.example.com
#   NAMI_ACME_EMAIL=me@example.com  NAMI_PORT=4533  NAMI_MUSIC_DIR=/srv/music
NAMI_MODE="${NAMI_MODE:-}"
NAMI_DOMAIN="${NAMI_DOMAIN:-}"
NAMI_ACME_EMAIL="${NAMI_ACME_EMAIL:-}"
NAMI_MUSIC_DIR="${NAMI_MUSIC_DIR:-}"
PORT="${NAMI_PORT:-$PORT}"

# Рамка намеренно без правого края: ширину пришлось бы считать в символах, а printf
# выравнивает по байтам, и любая кириллическая строка разъезжалась бы. Левый рельс
# и горизонтальные линии дают тот же вид без арифметики по ширине текста.
TUI_RULE="──────────────────────────────────────────────────────────────────────"

tui_line()  { printf "${CYAN}│${NC} %s\n" "$1"; }
tui_top()   { printf "${CYAN}╭%s${NC}\n" "$TUI_RULE"; }
tui_sep()   { printf "${CYAN}├%s${NC}\n" "$TUI_RULE"; }
tui_bottom(){ printf "${CYAN}╰%s${NC}\n" "$TUI_RULE"; }

tui_title() {
    echo
    tui_top
    printf "${CYAN}│${NC} ${BOLD}%s${NC}\n" "$1"
    tui_sep
}

# Меню со стрелками. Выбор возвращается в MENU_CHOICE (нумерация с 1).
# Без терминала вопрос не задаётся - берётся первый пункт.
tui_menu() {
    local title="$1"; shift
    local -a items=("$@")
    local n=${#items[@]}
    local cur=0 key rest

    if [ -z "$TTY_IN" ]; then
        MENU_CHOICE=1
        return
    fi

    while true; do
        tui_title "$title"
        local i=0
        while [ $i -lt $n ]; do
            if [ $i -eq $cur ]; then
                printf "${CYAN}│${NC} ${GREEN}❯${NC} ${BOLD}%s${NC}\n" "${items[$i]}"
            else
                printf "${CYAN}│${NC}   %s\n" "${items[$i]}"
            fi
            i=$((i + 1))
        done
        tui_sep
        tui_line "↑/↓ или 1-${n} — выбор, Enter — подтвердить"
        tui_bottom

        IFS= read -rsn1 key < "$TTY_IN" || { MENU_CHOICE=$((cur + 1)); return; }
        case "$key" in
            $'\x1b')
                # Стрелки приходят как ESC [ A / ESC [ B - дочитываем хвост.
                IFS= read -rsn2 -t 0.1 rest < "$TTY_IN" || rest=""
                case "$rest" in
                    '[A') cur=$(( (cur - 1 + n) % n ));;
                    '[B') cur=$(( (cur + 1) % n ));;
                esac
                ;;
            '') MENU_CHOICE=$((cur + 1)); return;;
            k)  cur=$(( (cur - 1 + n) % n ));;
            j)  cur=$(( (cur + 1) % n ));;
            [1-9])
                if [ "$key" -le "$n" ]; then MENU_CHOICE=$key; return; fi
                ;;
        esac
        # Перерисовываем поверх предыдущего кадра. Высота: пустая строка + верх +
        # заголовок + разделитель (4) + пункты (n) + разделитель + подсказка + низ (3).
        printf '\033[%dA\033[J' "$((n + 7))"
    done
}

# tui_ask <подсказка> <значение-по-умолчанию> <имя-переменной>
tui_ask() {
    local prompt="$1" def="$2" var="$3" val=""
    if [ -z "$TTY_IN" ]; then
        printf -v "$var" '%s' "$def"
        return
    fi
    if [ -n "$def" ]; then
        printf "${CYAN}?${NC} ${BOLD}%s${NC} [${CYAN}%s${NC}]: " "$prompt" "$def" > "$TTY_IN"
    else
        printf "${CYAN}?${NC} ${BOLD}%s${NC}: " "$prompt" > "$TTY_IN"
    fi
    IFS= read -r val < "$TTY_IN" || val=""
    [ -z "$val" ] && val="$def"
    printf -v "$var" '%s' "$val"
}

tui_confirm() {
    local prompt="$1" def="${2:-y}" val=""
    if [ -z "$TTY_IN" ]; then
        [ "$def" = "y" ]
        return
    fi
    printf "${CYAN}?${NC} ${BOLD}%s${NC} [%s]: " "$prompt" "$([ "$def" = y ] && echo 'Y/n' || echo 'y/N')" > "$TTY_IN"
    IFS= read -r val < "$TTY_IN" || val=""
    [ -z "$val" ] && val="$def"
    case "$val" in [yYдД]*) return 0;; *) return 1;; esac
}

# Домен годится для Let's Encrypt, только если это имя, а не IP, и в нём есть точка.
valid_domain() {
    case "$1" in
        ''|*[!A-Za-z0-9.-]*) return 1;;   # пусто или посторонние символы
        *.*) ;;                            # точка обязательна
        *) return 1;;
    esac
    case "$1" in
        *[A-Za-z]*) return 0;;             # есть буквы - это имя
        *) return 1;;                      # только цифры и точки - это IP
    esac
}

if [ "$IS_UPDATE" != true ] && [ -z "$NAMI_MODE" ] && [ -n "$TTY_IN" ]; then
    tui_menu "Как сервер будет доступен?" \
        "Домен + HTTPS        — сертификат Let's Encrypt, вход по https://домен" \
        "Локальная сеть       — самоподписанный сертификат, вход по IP" \
        "Свой обратный прокси — сервер на 127.0.0.1, TLS терминирует прокси"
    case "$MENU_CHOICE" in
        1) NAMI_MODE="domain";;
        2) NAMI_MODE="lan";;
        3) NAMI_MODE="proxy";;
    esac
fi
[ -z "$NAMI_MODE" ] && NAMI_MODE="lan"

if [ "$NAMI_MODE" = "domain" ]; then
    while [ -z "$NAMI_DOMAIN" ] || ! valid_domain "$NAMI_DOMAIN"; do
        tui_ask "Домен (A-запись уже должна вести на этот сервер)" "" NAMI_DOMAIN
        if [ -z "$TTY_IN" ]; then break; fi
        if ! valid_domain "$NAMI_DOMAIN"; then
            log_warn "Нужно доменное имя, например music.example.com — на IP сертификат не выдаётся."
            NAMI_DOMAIN=""
        fi
    done
    [ -z "$NAMI_ACME_EMAIL" ] && tui_ask "E-mail для Let's Encrypt (уведомления об истечении)" "admin@${NAMI_DOMAIN}" NAMI_ACME_EMAIL
elif [ "$NAMI_MODE" = "proxy" ] && [ -z "$NAMI_DOMAIN" ]; then
    tui_ask "Внешний домен, по которому прокси отдаёт сервер (можно пропустить)" "" NAMI_DOMAIN
fi

if [ "$IS_UPDATE" != true ]; then
    [ -z "$NAMI_MUSIC_DIR" ] && tui_ask "Папка с музыкой" "/srv/music" NAMI_MUSIC_DIR
    tui_ask "Порт сервера" "$PORT" PORT
fi

# 5. Автоматическая установка всех необходимых системных компонентов
echo
HAS_NGINX=false
if command -v nginx >/dev/null 2>&1 || [ -d /etc/nginx ] || (command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx 2>/dev/null); then
    HAS_NGINX=true
    log_info "Обнаружен веб-сервер Nginx — будет использоваться существующий Nginx + Certbot для SSL."
    # Если caddy был запущен ранее и конфликтует с nginx, остановим его
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet caddy 2>/dev/null; then
        $SUDO systemctl stop caddy >/dev/null 2>&1 || true
        $SUDO systemctl disable caddy >/dev/null 2>&1 || true
    fi
else
    log_info "Автоматическая установка системных компонентов (ffmpeg, Caddy, утилиты)..."
fi

PKG_MANAGER=""
if command -v apt-get >/dev/null 2>&1; then
    PKG_MANAGER="apt"
elif command -v dnf >/dev/null 2>&1; then
    PKG_MANAGER="dnf"
elif command -v yum >/dev/null 2>&1; then
    PKG_MANAGER="yum"
elif command -v pacman >/dev/null 2>&1; then
    PKG_MANAGER="pacman"
elif command -v apk >/dev/null 2>&1; then
    PKG_MANAGER="apk"
elif command -v zypper >/dev/null 2>&1; then
    PKG_MANAGER="zypper"
fi

case "$PKG_MANAGER" in
    apt)
        log_info "Обновление списка пакетов (apt)..."
        export DEBIAN_FRONTEND=noninteractive
        $SUDO apt-get update -y >/dev/null 2>&1 || true
        
        log_info "Установка ffmpeg, сертификатов и базовых утилит..."
        $SUDO apt-get install -y \
            curl \
            wget \
            tar \
            ca-certificates \
            gnupg \
            debian-keyring \
            debian-archive-keyring \
            apt-transport-https \
            ffmpeg \
            libchromaprint-tools >/dev/null 2>&1 || true
        
        if [ "$HAS_NGINX" = true ]; then
            log_info "Установка Certbot для Nginx..."
            $SUDO apt-get install -y certbot python3-certbot-nginx >/dev/null 2>&1 || true
        elif ! command -v caddy >/dev/null 2>&1; then
            log_info "Настройка репозитория и установка Caddy..."
            curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | $SUDO gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg 2>/dev/null || true
            curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | $SUDO tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null 2>&1 || true
            $SUDO apt-get update -y >/dev/null 2>&1 || true
            $SUDO apt-get install -y caddy >/dev/null 2>&1 || true
        fi
        ;;
        
    dnf|yum)
        log_info "Установка пакетов через $PKG_MANAGER..."
        $SUDO $PKG_MANAGER install -y curl wget tar ca-certificates gnupg2 ffmpeg >/dev/null 2>&1 || true
        if [ "$HAS_NGINX" = true ]; then
            log_info "Установка Certbot для Nginx..."
            $SUDO $PKG_MANAGER install -y certbot python3-certbot-nginx >/dev/null 2>&1 || true
        elif ! command -v caddy >/dev/null 2>&1; then
            log_info "Настройка репозитория Caddy..."
            $SUDO $PKG_MANAGER install -y 'dnf-command(copr)' >/dev/null 2>&1 || true
            $SUDO $PKG_MANAGER copr enable -y @caddy/caddy >/dev/null 2>&1 || true
            $SUDO $PKG_MANAGER install -y caddy >/dev/null 2>&1 || true
        fi
        ;;
        
    pacman)
        log_info "Установка пакетов через pacman..."
        if [ "$HAS_NGINX" = true ]; then
            $SUDO pacman -Sy --noconfirm curl wget tar ca-certificates ffmpeg chromaprint certbot certbot-nginx >/dev/null 2>&1 || true
        else
            $SUDO pacman -Sy --noconfirm curl wget tar ca-certificates ffmpeg caddy chromaprint >/dev/null 2>&1 || true
        fi
        ;;
        
    apk)
        log_info "Установка пакетов через apk..."
        $SUDO apk update >/dev/null 2>&1 || true
        if [ "$HAS_NGINX" = true ]; then
            $SUDO apk add --no-cache curl wget tar ca-certificates ffmpeg certbot certbot-nginx >/dev/null 2>&1 || true
        else
            $SUDO apk add --no-cache curl wget tar ca-certificates ffmpeg caddy >/dev/null 2>&1 || true
        fi
        ;;
        
    zypper)
        log_info "Установка пакетов через zypper..."
        $SUDO zypper refresh >/dev/null 2>&1 || true
        if [ "$HAS_NGINX" = true ]; then
            $SUDO zypper --non-interactive install curl wget tar ca-certificates ffmpeg certbot python3-certbot-nginx >/dev/null 2>&1 || true
        else
            $SUDO zypper --non-interactive install curl wget tar ca-certificates ffmpeg caddy >/dev/null 2>&1 || true
        fi
        ;;
        
    *)
        log_warn "Пакетный менеджер не определён, пробуем прямую установку..."
        ;;
esac

# Универсальный fallback для Caddy (скачивание официального бинарника, только если нет Nginx)
if [ "$HAS_NGINX" != true ] && ! command -v caddy >/dev/null 2>&1; then
    log_info "Загрузка официального исполняемого файла Caddy..."
    CADDY_ARCH="amd64"
    [ "$TARGET_ARCH" = "aarch64" ] && CADDY_ARCH="arm64"
    if curl -fsSL "https://caddyserver.com/api/download?os=linux&arch=${CADDY_ARCH}" -o "/tmp/caddy_bin" 2>/dev/null || \
       wget -qO "/tmp/caddy_bin" "https://caddyserver.com/api/download?os=linux&arch=${CADDY_ARCH}" 2>/dev/null; then
        $SUDO install -m 755 "/tmp/caddy_bin" /usr/local/bin/caddy
        rm -f "/tmp/caddy_bin"
    fi
fi

# Универсальный fallback для FFmpeg (статический бинарник)
if ! command -v ffmpeg >/dev/null 2>&1; then
    log_info "Загрузка статической сборки ffmpeg..."
    FFMPEG_ARCH="amd64"
    [ "$TARGET_ARCH" = "aarch64" ] && FFMPEG_ARCH="arm64"
    FFMPEG_TAR="/tmp/ffmpeg-release.tar.xz"
    if curl -fsSL "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-${FFMPEG_ARCH}-static.tar.xz" -o "$FFMPEG_TAR" 2>/dev/null; then
        mkdir -p /tmp/ffmpeg_ext
        if tar -xJf "$FFMPEG_TAR" -C /tmp/ffmpeg_ext --strip-components=1 2>/dev/null; then
            if [ -f "/tmp/ffmpeg_ext/ffmpeg" ]; then
                $SUDO install -m 755 "/tmp/ffmpeg_ext/ffmpeg" /usr/local/bin/ffmpeg
                [ -f "/tmp/ffmpeg_ext/ffprobe" ] && $SUDO install -m 755 "/tmp/ffmpeg_ext/ffprobe" /usr/local/bin/ffprobe
            fi
        fi
        rm -rf /tmp/ffmpeg_ext "$FFMPEG_TAR"
    fi
fi

# Итог проверки компонентов
echo
log_info "Статус установленных компонентов:"
if command -v ffmpeg >/dev/null 2>&1; then
    log_ok "ffmpeg:        $(ffmpeg -version 2>/dev/null | head -n1 | cut -d' ' -f1-3)"
else
    log_warn "ffmpeg:        не установлен (транскодинг будет недоступен)"
fi

if [ "$HAS_NGINX" = true ]; then
    log_ok "Веб-сервер:    Nginx обнаружен ($(nginx -v 2>&1 | cut -d/ -f2 || echo 'готов к работе'))"
    if command -v certbot >/dev/null 2>&1; then
        log_ok "Certbot:       установлен"
    else
        log_warn "Certbot:       не найден (будет установлен автоматически при привязке домена)"
    fi
elif command -v caddy >/dev/null 2>&1; then
    log_ok "Caddy:         $(caddy version 2>/dev/null | head -n1 || echo 'готов к работе')"
else
    log_warn "Caddy:         не установлен (потребуется ручная установка для авто-SSL)"
fi

if command -v curl >/dev/null 2>&1; then
    log_ok "curl:          $(curl --version 2>/dev/null | head -n1 | cut -d' ' -f1-2)"
fi
echo

# 6. Получение бинарника (GitHub Release или fallback на cargo)
TMP_DIR="$(mktemp -d /tmp/nami-install-XXXXXX)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

BINARY_FOUND=false
ASSET_NAME="nami-server-linux-${TARGET_ARCH}.tar.gz"
DOWNLOAD_SUCCESS=false
SPECIFIED_VERSION="$EARLY_REQUESTED_VERSION"
CURRENT_VERSION="$EARLY_CURRENT_VERSION"

log_info "Поиск релиза в репозитории ${GITHUB_REPO}..."

DOWNLOAD_URL=""

if [ -n "$SPECIFIED_VERSION" ]; then
    log_info "Используется указанная версия: ${SPECIFIED_VERSION}"
    DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${SPECIFIED_VERSION}/${ASSET_NAME}"
fi

# 1. Пробуем получить через GitHub API список всех релизов (включая pre-releases)
if [ -z "$DOWNLOAD_URL" ]; then
    RELEASES_API_URL="https://api.github.com/repos/${GITHUB_REPO}/releases"
    RELEASE_JSON="$(curl -fsSL -H "Accept: application/vnd.github+json" "$RELEASES_API_URL" 2>/dev/null || true)"
    if [ -n "$RELEASE_JSON" ]; then
        DOWNLOAD_URL="$(echo "$RELEASE_JSON" | grep -o "https://[^\"]*releases/download/[^\"]*${ASSET_NAME}" | head -n1 || true)"
    fi
fi

# 2. Если API вернуло пустоту (например, rate-limit), пробуем получить последний тег
if [ -z "$DOWNLOAD_URL" ]; then
    TAGS_API_URL="https://api.github.com/repos/${GITHUB_REPO}/tags"
    LATEST_TAG="$(curl -fsSL "$TAGS_API_URL" 2>/dev/null | grep -o '"name": *"[^"]*"' | head -n1 | cut -d'"' -f4 || true)"
    if [ -n "$LATEST_TAG" ]; then
        DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${LATEST_TAG}/${ASSET_NAME}"
    fi
fi

# 3. Финальный fallback на текущую актуальную бета-версию
if [ -z "$DOWNLOAD_URL" ]; then
    DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/v0.1.2-beta.1/${ASSET_NAME}"
fi

TARGET_VERSION="${SPECIFIED_VERSION:-$(printf '%s' "$DOWNLOAD_URL" | sed -n 's#.*/releases/download/\([^/]*\)/.*#\1#p')}"
if [ -n "$CURRENT_VERSION" ] && [ "${CURRENT_VERSION#v}" = "${TARGET_VERSION#v}" ]; then
    log_ok "Обновления нет: у вас уже установлена последняя версия ${CURRENT_VERSION#v}."
    exit 0
fi
if [ -n "$CURRENT_VERSION" ]; then
    log_info "Будет выполнено обновление: ${CURRENT_VERSION#v} → ${TARGET_VERSION#v}."
fi

log_info "Попытка скачивания бинарного архива: ${ASSET_NAME}..."
log_info "URL: ${DOWNLOAD_URL}"

if curl -fSL --connect-timeout 15 --retry 3 "$DOWNLOAD_URL" -o "${TMP_DIR}/${ASSET_NAME}"; then
    log_info "Распаковка архива..."
    if tar -xzf "${TMP_DIR}/${ASSET_NAME}" -C "$TMP_DIR" 2>/dev/null; then
        if [ -f "${TMP_DIR}/nami-server" ]; then
            BINARY_FOUND=true
            DOWNLOAD_SUCCESS=true
            log_ok "Бинарный файл nami-server успешно получен из релиза."
        fi
    fi
else
    log_warn "Загрузка по прямому URL не удалась."
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
if [ -n "$CURRENT_VERSION" ]; then
    log_ok "Сервер обновлён: ${CURRENT_VERSION#v} → ${TARGET_VERSION#v}."
else
    log_ok "Установлена версия ${TARGET_VERSION#v}."
fi

# 8. Создание системного пользователя nami и настройка sudoers
if ! id -u nami >/dev/null 2>&1; then
    log_info "Создание системного пользователя 'nami'..."
    $SUDO useradd -r -s /usr/sbin/nologin -d "$DATA_DIR" -M nami 2>/dev/null || \
    $SUDO useradd -r -s /bin/false -d "$DATA_DIR" -M nami 2>/dev/null || \
    $SUDO adduser --system --no-create-home --group --shell /usr/sbin/nologin nami 2>/dev/null || true
    log_ok "Пользователь 'nami' создан."
else
    log_info "Системный пользователь 'nami' уже существует."
fi

# Настройка sudoers для nami, чтобы веб-мастер /setup мог настраивать Nginx, Caddy, Certbot и фаервол
if [ -d /etc/sudoers.d ]; then
    log_info "Настройка прав sudoers для пользователя nami..."
    cat << 'EOF' | $SUDO tee /etc/sudoers.d/nami > /dev/null
nami ALL=(ALL) NOPASSWD: ALL
EOF
    $SUDO chmod 0440 /etc/sudoers.d/nami
fi

# 9. Настройка директории данных и каталога конфигурации caddy
log_info "Настройка директории данных ${DATA_DIR}..."
$SUDO mkdir -p "$DATA_DIR"
$SUDO chown -R nami:nami "$DATA_DIR"
$SUDO chmod 750 "$DATA_DIR"
printf '%s\n' "${TARGET_VERSION#v}" | $SUDO tee "${DATA_DIR}/version" >/dev/null

$SUDO mkdir -p /etc/caddy
$SUDO chown -R nami:nami /etc/caddy 2>/dev/null || true
$SUDO chmod 775 /etc/caddy 2>/dev/null || true

# 9a. Предзаполнение config.toml по ответам из TUI, чтобы /setup открывался сразу по
# нужному адресу, а не после ручной правки конфига.
if [ "$IS_UPDATE" != true ] && [ ! -f "${DATA_DIR}/config.toml" ]; then
    case "$NAMI_MODE" in
        domain|proxy)
            # TLS снимает прокси настоящим сертификатом, сам сервер слушает открытый HTTP
            # на localhost - иначе прокси пришлось бы ходить в самоподписанный сертификат.
            CFG_TLS=false
            if [ -n "$NAMI_DOMAIN" ]; then
                CFG_EXTERNAL="https://${NAMI_DOMAIN}"
            else
                CFG_EXTERNAL=""
            fi
            ;;
        *)
            CFG_TLS=true
            CFG_EXTERNAL=""
            ;;
    esac
    log_info "Создание ${DATA_DIR}/config.toml..."
    {
        printf 'port = %s\n' "$PORT"
        printf 'tls = %s\n' "$CFG_TLS"
        [ -n "$CFG_EXTERNAL" ] && printf 'external_url = "%s"\n' "$CFG_EXTERNAL"
        if [ -n "$NAMI_MUSIC_DIR" ]; then
            $SUDO mkdir -p "$NAMI_MUSIC_DIR" 2>/dev/null || true
            printf 'music_dirs = ["%s"]\n' "$NAMI_MUSIC_DIR"
        fi
    } | $SUDO tee "${DATA_DIR}/config.toml" > /dev/null
    $SUDO chown nami:nami "${DATA_DIR}/config.toml"
fi

# 9b. Caddy: домен + автоматический сертификат Let's Encrypt.
if [ "$NAMI_MODE" = "domain" ] && [ -n "$NAMI_DOMAIN" ]; then
    if [ "$HAS_NGINX" = true ]; then
        log_warn "Обнаружен Nginx — он уже занимает порты 80/443, поэтому Caddy не настраивается."
        log_warn "Добавьте в конфигурацию Nginx proxy_pass на http://127.0.0.1:${PORT} для ${NAMI_DOMAIN}"
        log_warn "и выпустите сертификат: sudo certbot --nginx -d ${NAMI_DOMAIN}"
    elif command -v caddy >/dev/null 2>&1; then
        log_info "Настройка Caddy для домена ${NAMI_DOMAIN}..."
        cat << EOF | $SUDO tee /etc/caddy/Caddyfile > /dev/null
{
	email ${NAMI_ACME_EMAIL}
}

# Сертификат Let's Encrypt Caddy получает и продлевает сам. Внутрь идёт открытый
# HTTP на localhost: config.toml в этом режиме ставит tls = false.
${NAMI_DOMAIN} {
	encode zstd gzip
	reverse_proxy 127.0.0.1:${PORT}
}
EOF
        $SUDO chown nami:nami /etc/caddy/Caddyfile 2>/dev/null || true
        if $SUDO caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1; then
            log_ok "Caddyfile проверен."
        else
            log_warn "Caddy не принял конфигурацию — проверьте /etc/caddy/Caddyfile вручную."
        fi
        $SUDO systemctl enable caddy >/dev/null 2>&1 || true
        $SUDO systemctl restart caddy >/dev/null 2>&1 || log_warn "Не удалось перезапустить caddy."
    else
        log_warn "Caddy не установлен — HTTPS для ${NAMI_DOMAIN} придётся настроить вручную."
    fi
fi

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

[Install]
WantedBy=multi-user.target
EOF

# 11. Активация и запуск службы
log_info "Перезагрузка systemd и запуск службы ${SERVICE_NAME}..."
$SUDO systemctl daemon-reload
$SUDO systemctl enable "${SERVICE_NAME}.service"
$SUDO systemctl restart "${SERVICE_NAME}.service"

# Открытие портов в брандмауэре. Набор зависит от режима: за прокси наружу смотрят
# только 80/443, в локальном режиме - порт самого сервера. Открытие наружу спрашиваем
# явно: на машине с публичным IP это выставляет сервис в интернет.
case "$NAMI_MODE" in
    domain) PORTS_TO_OPEN=(80 443);;
    proxy)  PORTS_TO_OPEN=();;
    *)      PORTS_TO_OPEN=($PORT);;
esac

if [ ${#PORTS_TO_OPEN[@]} -gt 0 ] && [ "$IS_UPDATE" != true ]; then
    if ! tui_confirm "Открыть порты ${PORTS_TO_OPEN[*]} в брандмауэре?" y; then
        PORTS_TO_OPEN=()
        log_info "Порты не трогаем — откройте их вручную, когда понадобится."
    fi
fi

if command -v ufw >/dev/null 2>&1; then
    if [ ${#PORTS_TO_OPEN[@]} -gt 0 ] && ufw status 2>/dev/null | grep -qw "active"; then
        log_info "Настройка UFW: открытие портов ${PORTS_TO_OPEN[*]}/tcp..."
        for p in "${PORTS_TO_OPEN[@]}"; do
            $SUDO ufw allow ${p}/tcp >/dev/null 2>&1 || true
        done
        log_ok "Порты ${PORTS_TO_OPEN[*]}/tcp разрешены в UFW."
    fi
fi
if command -v firewall-cmd >/dev/null 2>&1; then
    if [ ${#PORTS_TO_OPEN[@]} -gt 0 ] && firewall-cmd --state 2>/dev/null | grep -qw "running"; then
        log_info "Настройка firewalld: открытие портов ${PORTS_TO_OPEN[*]}/tcp..."
        for p in "${PORTS_TO_OPEN[@]}"; do
            $SUDO firewall-cmd --add-port=${p}/tcp --permanent >/dev/null 2>&1 || true
        done
        $SUDO firewall-cmd --reload >/dev/null 2>&1 || true
        log_ok "Порты ${PORTS_TO_OPEN[*]}/tcp разрешены в firewalld."
    fi
fi

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

# После обновления конфигурация уже существует. Показываем внешний адрес, который
# пользователь реально задал, а LAN-IP используем только когда домена нет.
EXTERNAL_URL=""
if [ -f "${DATA_DIR}/config.toml" ]; then
    EXTERNAL_URL="$($SUDO sed -n 's/^[[:space:]]*external_url[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "${DATA_DIR}/config.toml" 2>/dev/null | tail -n1 || true)"
fi
if [ -n "$EXTERNAL_URL" ]; then
    WEB_URL="${EXTERNAL_URL%/}"
elif [ "$NAMI_MODE" = "domain" ] && [ -n "$NAMI_DOMAIN" ]; then
    WEB_URL="https://${NAMI_DOMAIN}"
else
    WEB_URL="https://${PRIMARY_IP}:${PORT}"
fi

# 13. Итоговое сообщение
echo
if [ "$IS_UPDATE" = true ]; then
    echo -e "${BOLD}${GREEN}======================================================================${NC}"
    echo -e "${BOLD}${GREEN}  🔄 Nami Server успешно обновлён и перезапущен!${NC}"
    echo -e "${BOLD}${GREEN}======================================================================${NC}"
    echo
    echo -e "  Служба ${BOLD}${SERVICE_NAME}${NC} обновлена: ${CURRENT_VERSION#v} → ${TARGET_VERSION#v}."
    echo -e "  Все пользовательские данные и база данных сохранены в ${CYAN}${DATA_DIR}${NC}."
    echo
    echo -e "  ${BOLD}Веб-интерфейс сервера:${NC}"
    echo -e "         👉 ${BOLD}${CYAN}${WEB_URL}${NC}"
    echo
    echo -e "${BOLD}Команды управления:${NC}"
    echo -e "  ${CYAN}nami status${NC}           - Проверка статуса сервера и здоровья API"
    echo -e "  ${CYAN}nami logs${NC}             - Просмотр журнала последних логов"
    echo -e "  ${CYAN}nami doctor${NC}           - Комплексная диагностика (порты, ffmpeg, БД)"
    echo -e "  ${CYAN}sudo systemctl status nami${NC} - Статус службы systemd"
    echo -e "${BOLD}${GREEN}======================================================================${NC}"
else
    echo -e "${BOLD}${GREEN}======================================================================${NC}"
    echo -e "${BOLD}${GREEN}  🎉 Nami Server успешно установлен и запущен!${NC}"
    echo -e "${BOLD}${GREEN}======================================================================${NC}"
    echo
    echo -e "  ${BOLD}ШАГ 1. Первичная настройка в браузере:${NC}"
    if [ "$NAMI_MODE" = "domain" ] && [ -n "$NAMI_DOMAIN" ]; then
        echo -e "         👉 ${BOLD}${CYAN}https://${NAMI_DOMAIN}/setup${NC}"
        echo -e "         ${GREEN}Сертификат Let's Encrypt выпускается автоматически — предупреждений нет.${NC}"
        echo -e "         ${YELLOW}Если страница не открылась сразу: A-запись ${NAMI_DOMAIN} должна вести${NC}"
        echo -e "         ${YELLOW}на ${PRIMARY_IP}, а порты 80 и 443 быть доступны снаружи.${NC}"
    elif [ "$NAMI_MODE" = "proxy" ]; then
        echo -e "         👉 ${BOLD}${CYAN}http://127.0.0.1:${PORT}/setup${NC} (через ваш обратный прокси)"
        echo -e "         ${YELLOW}Сервер слушает открытый HTTP на localhost — TLS терминирует прокси.${NC}"
    else
        echo -e "         👉 ${BOLD}${CYAN}https://${PRIMARY_IP}:${PORT}/setup${NC}"
        echo -e "         (или локально: ${CYAN}https://localhost:${PORT}/setup${NC})"
        echo -e "         ${YELLOW}Примечание: Браузер предупредит о самоподписанном сертификате.${NC}"
        echo -e "         ${YELLOW}Нажмите «Дополнительно» → «Перейти на сайт» (все пароли шифруются TLS).${NC}"
    fi
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
    echo -e "         • Снова откройте ${CYAN}${WEB_URL}/setup${NC}"
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
fi
echo
