<p align="center"><img src="assets/icon-wave.png" width="112" alt="Nami" /></p>
<h1 align="center">Nami</h1>
<p align="center"><strong>your music, your day.</strong></p>

Nami — музыкальный плеер для Android для тех, кто слушает свою коллекцию: FLAC, Hi-Res, bit-perfect через USB-ЦАП, тексты с караоке, эквалайзер, статистика и итоги. Работает без интернета и без аккаунта.

Если хочется слушать свою музыку с любого устройства, рядом можно поставить **Nami Server** — свой личный стриминг: библиотека, лайки, плейлисты и история синхронизируются между устройствами, можно слушать вместе с друзьями (Jam) и показывать трек в Discord.

Здесь лежат релизы, установка, список изменений и оформление итогов. Исходный код закрыт.

## Установить приложение

Скачай `nami-*.apk` из [последнего релиза](https://github.com/MozzarellaCheesee/nami/releases/latest) и открой на телефоне. Дальше приложение обновляется само: Настройки → Обновления.

## Поставить сервер

**Linux (systemd):**
```bash
curl -fsSL https://raw.githubusercontent.com/MozzarellaCheesee/nami/main/install.sh | bash
```

**Windows (PowerShell от администратора):**
```powershell
irm https://raw.githubusercontent.com/MozzarellaCheesee/nami/main/install.ps1 | iex
```

**Docker:**
```bash
docker run -d --name nami -p 4533:4533 -v /path/to/music:/music:ro -v nami-data:/data ghcr.io/mozzarellacheesee/nami-server:latest
```

После установки открой веб-панель сервера, создай аккаунт и отсканируй QR-код из приложения (Настройки → Сервер). С телефона можно просто нажать «Подключить» в веб-панели.

## Что где

- [Релизы](https://github.com/MozzarellaCheesee/nami/releases) — APK и сборки сервера.
- [CHANGELOG.md](CHANGELOG.md) — что поменялось.
- [recaps/](recaps/) — оформление итогов месяца и года (приложение подтягивает их отсюда без обновления).
- [Issues](https://github.com/MozzarellaCheesee/nami/issues) — баги и идеи.

## Поддержать

https://www.donationalerts.com/r/mozzarellacheese
