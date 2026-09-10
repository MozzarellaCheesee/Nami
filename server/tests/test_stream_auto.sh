#!/bin/bash
# Тест умного стриминга /api/tracks/{id}/stream/auto

BASE_URL="http://localhost:3000"
TOKEN="your_device_token_here"
TRACK_ID=1

echo "=== Тест 1: Wi-Fi через query параметр ==="
curl -i -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/tracks/$TRACK_ID/stream/auto?network=wifi" \
  -o /dev/null -w "HTTP: %{http_code}\nContent-Type: %{content_type}\n\n"

echo "=== Тест 2: Cellular через query параметр ==="
curl -i -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/tracks/$TRACK_ID/stream/auto?network=cellular" \
  -o /dev/null -w "HTTP: %{http_code}\nContent-Type: %{content_type}\n\n"

echo "=== Тест 3: Android User-Agent без wifi ==="
curl -i -H "Authorization: Bearer $TOKEN" \
  -H "User-Agent: Nami/1.0 (Android 14; Pixel 7)" \
  "$BASE_URL/api/tracks/$TRACK_ID/stream/auto" \
  -o /dev/null -w "HTTP: %{http_code}\nContent-Type: %{content_type}\n\n"

echo "=== Тест 4: Desktop User-Agent (fallback на Wi-Fi) ==="
curl -i -H "Authorization: Bearer $TOKEN" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)" \
  "$BASE_URL/api/tracks/$TRACK_ID/stream/auto" \
  -o /dev/null -w "HTTP: %{http_code}\nContent-Type: %{content_type}\n\n"

echo "=== Тест 5: iPhone User-Agent без wifi ==="
curl -i -H "Authorization: Bearer $TOKEN" \
  -H "User-Agent: Nami/1.0 (iPhone; iOS 17.0)" \
  "$BASE_URL/api/tracks/$TRACK_ID/stream/auto" \
  -o /dev/null -w "HTTP: %{http_code}\nContent-Type: %{content_type}\n\n"
