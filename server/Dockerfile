# Multiarch-образ. Сборка идёт НАТИВНО на архитектуре хоста (BUILDPLATFORM), а бинарник
# кросс-компилируется под TARGETPLATFORM - это на порядок быстрее, чем гонять весь rustc
# под QEMU. Проверено:
#   docker buildx build --platform linux/amd64,linux/arm64 -t nami-server .
FROM --platform=$BUILDPLATFORM rust:1-slim AS build
ARG TARGETARCH
# rusqlite (feature bundled) и ring собирают C-код - нужен компилятор под целевую архитектуру.
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc libc6-dev gcc-aarch64-linux-gnu gcc-x86-64-linux-gnu \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
# Триплет и линкер выбираются по TARGETARCH, который buildx подставляет сам.
RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) TRIPLE=x86_64-unknown-linux-gnu; CC=x86_64-linux-gnu-gcc ;; \
      arm64) TRIPLE=aarch64-unknown-linux-gnu; CC=aarch64-linux-gnu-gcc ;; \
      *) echo "не поддерживаемая архитектура: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    rustup target add "$TRIPLE"; \
    TU=$(echo "$TRIPLE" | tr 'a-z-' 'A-Z_'); \
    TL=$(echo "$TRIPLE" | tr '-' '_'); \
    export CARGO_TARGET_${TU}_LINKER="$CC"; \
    export CC_${TL}="$CC"; \
    cargo build --release --target "$TRIPLE"; \
    cp "target/$TRIPLE/release/nami-server" /nami-server

FROM debian:stable-slim
# ffmpeg нужен для транскодинга (профили opus/aac). Без него сервер работает,
# но /api/tracks/{id}/stream?profile=... отвечает 503.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /nami-server /usr/local/bin/nami-server
# /music - библиотека (только чтение), /data - БД, TLS-сертификат и кеш транскодов.
ENV NAMI_MUSIC_DIRS=/music NAMI_DB_PATH=/data/nami.db NAMI_DATA_DIR=/data NAMI_PORT=4533
VOLUME ["/data"]
EXPOSE 4533
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/nami-server"]
