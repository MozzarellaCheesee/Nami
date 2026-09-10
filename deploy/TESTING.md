# Примечания для разработчиков

## Тестирование шаблонов

### Unraid
Шаблон протестировать в живой системе или через Unraid VM:
```bash
# Валидация XML
xmllint --noout deploy/unraid/nami.xml
```

### TrueNAS SCALE (Helm)
```bash
cd deploy/truenas/nami-chart

# Проверка синтаксиса
helm lint .

# Dry-run установки
helm install nami . --dry-run --debug

# Рендер шаблонов
helm template nami . > rendered.yaml
```

### Synology
```bash
cd deploy/synology

# Валидация docker-compose
docker compose config

# Проверка без запуска
docker compose --dry-run up
```

## Публикация

### Docker образ
Перед публикацией шаблонов убедитесь, что образ доступен:
```bash
# Сборка multi-arch
docker buildx build --platform linux/amd64,linux/arm64 \
  -t ghcr.io/nami/nami-server:latest \
  -f server/Dockerfile \
  --push .
```

### Helm chart
```bash
# Упаковка
helm package deploy/truenas/nami-chart

# Публикация в репозиторий
# (требуется настройка GitHub Pages или Artifact Hub)
```

## Обновление версий

При выпуске новой версии обновить:
1. `Chart.yaml` - `version` и `appVersion`
2. `values.yaml` - `image.tag` (если не latest)
3. `README.md` - инструкции при изменении API

## Порт по умолчанию

Во всех шаблонах используется **порт 4533** (из `server/src/config.rs`).
Если в будущем порт изменится, обновить все три шаблона.
