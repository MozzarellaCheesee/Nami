<p align="right">
  <strong>Русский</strong> · <a href="README.en.md">English</a>
</p>

# Развёртывание Nami на NAS

Шаблоны для популярных NAS-систем.

## Unraid

`unraid/nami.xml` - шаблон для Community Applications.

1. Скопировать `nami.xml` в `/boot/config/plugins/dockerMan/templates-user/`
2. В Docker UI: Add Container → выбрать Nami из User Templates
3. Настроить пути к музыке и данным
4. Apply

## TrueNAS SCALE

`truenas/nami-chart/` - Helm chart для TrueNAS SCALE.

1. В TrueNAS: Apps → Manage Catalogs → Add Catalog
2. Указать репозиторий с чартом
3. Найти Nami в каталоге → Install
4. Настроить в GUI:
   - `persistence.music.hostPath` - путь к музыке
   - `persistence.data.hostPath` - путь к данным
   - `persistence.cache.hostPath` - путь к кешу
5. Deploy

Альтернатива через CLI:
```bash
helm install nami ./nami-chart \
  --set persistence.music.hostPath=/mnt/tank/Music \
  --set persistence.data.hostPath=/mnt/tank/appdata/nami/data \
  --set persistence.cache.hostPath=/mnt/tank/appdata/nami/cache
```

## Synology

`synology/docker-compose.yml` - для Container Manager.

1. В DSM: открыть Container Manager
2. Project → Create
3. Загрузить `docker-compose.yml`
4. Отредактировать пути томов под свою структуру:
   - `/volume1/Music` - музыкальная коллекция
   - `/volume1/docker/nami/data` - данные
   - `/volume1/docker/nami/cache` - кеш
5. Build and Start

## Общие настройки

Все шаблоны используют:
- **Порт**: 3030 (HTTP + WebSocket)
- **Тома**:
  - `/music` - музыкальная коллекция (read-only)
  - `/data` - БД, конфиг, обложки (read-write)
  - `/cache` - лирика, метаданные (read-write)
- **Health check**: `GET /health` каждые 30 секунд
- **Restart policy**: unless-stopped / Always
- **Переменные окружения**:
  - `TZ` - timezone (по умолчанию Europe/Moscow)
  - `RUST_LOG` - уровень логирования (info/debug/trace)
  - `PUID/PGID` - UID/GID для доступа к файлам

## Требования

- Docker образ: `ghcr.io/your-org/nami-server:latest`
- Минимум 256 МБ RAM, рекомендуется 512 МБ
- Порт 3030 свободен
- Доступ к музыкальной коллекции
