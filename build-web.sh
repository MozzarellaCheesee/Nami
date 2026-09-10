#!/bin/bash
# Сборка PWA-клиента перед релизом сервера

set -e

cd "$(dirname "$0")/web"

echo "Установка зависимостей..."
npm install

echo "Сборка PWA-клиента..."
npm run build

echo "Готово: web/dist собран и будет встроен в бинарник сервера"
