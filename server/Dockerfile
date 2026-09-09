# Сборка нативная для текущей платформы образа. Multiarch делается снаружи:
#   docker buildx build --platform linux/amd64,linux/arm64 -t nami-server .
# (buildx подставляет QEMU, кросс-линкеры в образ не тащим - для одного бинарника
# это дало бы больше возни с sysroot, чем пользы; отдельный шаг, если сборка arm64
# под эмуляцией окажется слишком медленной на практике).
FROM rust:1-slim AS build
# rusqlite собирает SQLite из исходников (feature bundled) - нужен C-компилятор.
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release

FROM debian:stable-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/nami-server /usr/local/bin/nami-server
# /music - библиотека (только чтение), /data - БД и TLS-сертификат.
ENV NAMI_MUSIC_DIRS=/music NAMI_DB_PATH=/data/nami.db NAMI_DATA_DIR=/data NAMI_PORT=4533
VOLUME ["/data"]
EXPOSE 4533
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/nami-server"]
