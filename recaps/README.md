# Итоги месяца и года

Приложение раз в запуск читает `recaps/index.json` отсюда и показывает итоги, у которых сейчас открыт период показа. Оформление, число экранов и тексты задаются здесь, данные каждый пользователь считает у себя. Если телефон подключён к серверу, данные берутся из полной истории на сервере, если нет, то из локальной истории. Обновлять приложение не нужно.

## Как выпустить итоги

1. Скопируй `example.json` в `<id>.json`, например `2026-09.json`.
2. Поменяй период, даты показа, цвета и экраны.
3. Допиши id в `index.json`: `["2026-09"]`.
4. Закоммить в `main`. Приложения увидят итоги при следующем открытии статистики.

## Формат

```jsonc
{
  "id": "2026-09",
  "title": "Итоги сентября",
  "subtitle": "Твой месяц в музыке",
  "from": "2026-09-01",            // период, включительно
  "to": "2026-09-30",
  "available_from": "2026-09-30",  // когда показывать (необязательно)
  "available_until": "2026-10-31",
  "min_plays": 10,                 // меньше прослушиваний - итоги не показываются
  "theme": { ... },                // стиль по умолчанию для всех экранов
  "screens": [ ... ]
}
```

### Стиль (`theme` и `style` экрана)

| ключ | значение |
|---|---|
| `background` | цвет фона `#RRGGBB` |
| `background2` | второй цвет: фон становится вертикальным градиентом |
| `image` | картинка на весь фон: URL или шаблон вроде `{{top_artists.1.artwork}}` |
| `dim` | затемнение поверх картинки, `0`–`1` |
| `text` | цвет текста |
| `accent` | цвет чисел, полос графиков и номеров |
| `font` | `sans`, `serif`, `mono` |

### Экран

```jsonc
{
  "duration_ms": 6000,               // сколько экран держится до автоперехода
  "show_if": "new_artists.1.title",  // экран пропускается, если этого значения нет или оно 0
  "style": { "background": "#101010" },
  "blocks": [ ... ]                  // сверху вниз, по центру экрана
}
```

### Блоки

| `type` | параметры |
|---|---|
| `text` | `text`, `size` (sp, по умолчанию 20), `weight` (`normal`, `bold`, `black`), `align` (`start`, `center`, `end`), `color`, `font` |
| `number` | `value` (шаблон), `label`, `size` (по умолчанию 64), `color`. Число набегает от нуля. |
| `list` | `source` (любой список ниже), `limit` (по умолчанию 5), `show` (`plays`, `minutes`, `none`), `artwork` (`true`/`false`) |
| `image` | `src` (URL или шаблон), `size` (dp, по умолчанию 200), `shape` (`circle`, `rounded`, `square`) |
| `bars` | `source` (`hours`, `weekdays`, `days`), `metric` (`minutes`, `plays`), `height` (dp, по умолчанию 140), `color` |
| `spacer` | `height` (dp) |

Во всех текстовых параметрах работают шаблоны:

- `{{minutes}}` — значение;
- `{{plural:minutes|минута|минуты|минут}}` — слово в нужной форме.

### Данные

Числа: `minutes`, `hours`, `plays`, `tracks`, `artists`, `albums`, `new_artists` (впервые услышанные в периоде), `active_days`, `streak_days` (самая длинная серия дней подряд), `period_days`.

Строки: `title`, `year`, `month`, `period_from`, `period_to`, `top_day` + `top_day_minutes`, `top_hour`, `top_weekday`, `first_track` + `first_track_artist` + `first_track_artwork`.

Списки: `top_tracks`, `top_artists`, `top_albums`, `new_artists`, `hours`, `weekdays`, `days`. К элементу списка можно обратиться по номеру с 1: `top_artists.1.title`, `.subtitle`, `.plays`, `.minutes`, `.artwork`.
