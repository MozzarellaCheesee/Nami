<p align="right">
  <a href="README.md">Русский</a> · <strong>English</strong>
</p>

# Deploying Nami on NAS

Templates for popular NAS platforms.

## Unraid

`unraid/nami.xml` - template for Community Applications.

1. Copy `nami.xml` to `/boot/config/plugins/dockerMan/templates-user/`
2. In the Docker UI: Add Container → select Nami from User Templates
3. Configure the music and data paths
4. Apply

## TrueNAS SCALE

`truenas/nami-chart/` - Helm chart for TrueNAS SCALE.

1. In TrueNAS: Apps → Manage Catalogs → Add Catalog
2. Specify the repository containing the chart
3. Find Nami in the catalog → Install
4. Configure in the GUI:
   - `persistence.music.hostPath` - path to the music library
   - `persistence.data.hostPath` - path to application data
   - `persistence.cache.hostPath` - path to the cache
5. Deploy

CLI alternative:
```bash
helm install nami ./nami-chart \
  --set persistence.music.hostPath=/mnt/tank/Music \
  --set persistence.data.hostPath=/mnt/tank/appdata/nami/data \
  --set persistence.cache.hostPath=/mnt/tank/appdata/nami/cache
```

## Synology

`synology/docker-compose.yml` - configuration for Container Manager.

1. In DSM, open Container Manager
2. Project → Create
3. Upload `docker-compose.yml`
4. Adjust the volume paths to match your system:
   - `/volume1/Music` - music library
   - `/volume1/docker/nami/data` - application data
   - `/volume1/docker/nami/cache` - cache
5. Build and Start

## Common settings

All templates use:
- **Port**: 3030 (HTTP + WebSocket)
- **Volumes**:
  - `/music` - music library (read-only)
  - `/data` - database, configuration, artwork (read-write)
  - `/cache` - lyrics and metadata cache (read-write)
- **Health check**: `GET /health` every 30 seconds
- **Restart policy**: unless-stopped / Always
- **Environment variables**:
  - `TZ` - timezone (defaults to Europe/Moscow)
  - `RUST_LOG` - logging level (info/debug/trace)
  - `PUID/PGID` - UID/GID used for file access

## Requirements

- Docker image: `ghcr.io/your-org/nami-server:latest`
- Minimum 256 MB RAM, 512 MB recommended
- Port 3030 must be available
- Access to the music library
