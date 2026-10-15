# syntax=docker/dockerfile:1
# The LWS clients Touchstone drives (github.com/ebremer/lws-client): the lws-client driver, an MCP server, with the
# adapter of every language it drives: Java, JavaScript, Python, Go, Rust, C++, C#, Swift, PHP, Kotlin, and the Rust
# client as a WebAssembly component in Wasmtime. Not a target server: interop-all.sh drives these clients against
# the targets (README.md, "Every client against every server"); entrypoint.sh lists the settings.
#
# The source is the repository at LWS_CLIENT_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/lws-client targetservers/clients

ARG LWS_CLIENT_REPO=https://github.com/ebremer/lws-client.git
ARG LWS_CLIENT_REF=99b8b10c148af84743b9db9c8e0d61426c7862f7

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG LWS_CLIENT_REPO
ARG LWS_CLIENT_REF
RUN git init -q /src && git -C /src remote add origin "$LWS_CLIENT_REPO" \
 && git -C /src fetch -q --depth 1 origin "$LWS_CLIENT_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- Java: the library, its adapter, and the driver ----
FROM maven:3.9.11-eclipse-temurin-25 AS java
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f driver/pom.xml -DskipTests package

# ---- JavaScript ----
FROM node:24.21.0-trixie-slim AS js
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/.npm cd js && npm ci --no-audit --no-fund && npm run build

# ---- Go ----
FROM golang:1.27.2-trixie AS go
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    cd driver/adapters/go && CGO_ENABLED=0 go build -trimpath -o /out/lws-driver-adapter-go .

# ---- Rust, and the WebAssembly component with its Wasmtime host ----
FROM rust:1.97.1-slim-trixie AS rust
RUN apt-get update && apt-get install -y --no-install-recommends cmake clang pkg-config libssl-dev \
 && rm -rf /var/lib/apt/lists/* && rustup target add wasm32-wasip2
WORKDIR /build
COPY --from=source / .
ENV CARGO_TARGET_DIR=/cargo-target
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/cargo-target \
    (cd driver/adapters/rust && cargo build --release --locked) \
 && (cd wasm && cargo build --release --locked --target wasm32-wasip2) \
 && (cd driver/adapters/wasm && cargo build --release --locked) \
 && mkdir -p /out && cp /cargo-target/release/lws-driver-adapter-rust /cargo-target/release/lws-driver-adapter-wasm \
      /cargo-target/wasm32-wasip2/release/lws_client.wasm /out/

# ---- C++ ----
FROM debian:trixie-20261005-slim AS cpp
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates g++ cmake ninja-build \
      nlohmann-json3-dev libcurl4-openssl-dev libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY --from=source / .
RUN cmake -S driver/adapters/cpp -B /cpp-build -G Ninja -DCMAKE_BUILD_TYPE=Release -DLWS_FETCH_DEPS=OFF \
 && cmake --build /cpp-build && mkdir -p /out && strip -o /out/lws-driver-adapter-cpp /cpp-build/lws-driver-adapter-cpp

# ---- C#: self-contained, one file ----
FROM mcr.microsoft.com/dotnet/sdk:10.0.402-noble AS csharp
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/.nuget/packages \
    dotnet publish driver/adapters/csharp -c Release -r linux-x64 --self-contained -p:PublishSingleFile=true -o /out

# ---- Swift: the Swift runtime and Foundation linked statically, so it needs only libcurl ----
FROM swift:6.4.1-noble AS swift
WORKDIR /build
COPY --from=source / .
RUN swift build -c release --product lws-driver-adapter-swift --static-swift-stdlib --build-system native \
 && mkdir -p /out && strip -o /out/lws-driver-adapter-swift .build/release/lws-driver-adapter-swift

# ---- Kotlin: a fat jar ----
FROM eclipse-temurin:25.0.4_7-jdk-noble AS kotlin
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/root/.gradle sh kotlin/gradlew --no-daemon -q -p driver/adapters/kotlin jar

# ---- runtime ----
FROM debian:trixie-20261005-slim
ARG LWS_CLIENT_REPO
ARG LWS_CLIENT_REF
LABEL org.opencontainers.image.title="lws-client driver" \
      org.opencontainers.image.description="The lws-client driver (MCP) with the adapters of all eleven lws-clients" \
      org.opencontainers.image.source="$LWS_CLIENT_REPO" \
      org.opencontainers.image.revision="$LWS_CLIENT_REF"
# The C++ and Swift adapters use libcurl, the PHP one ext-curl, ext-openssl and ext-sodium (built into Debian's PHP).
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libcurl4t64 libssl3t64 \
      python3 python3-venv php8.4-cli php8.4-curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /opt/lws-client --shell /usr/sbin/nologin lws
COPY --from=eclipse-temurin:25.0.4_7-jre-noble /opt/java/openjdk /opt/java/openjdk
COPY --from=node:24.21.0-trixie-slim /usr/local/bin/node /usr/local/bin/node
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
WORKDIR /opt/lws-client
# Python: the library in a venv of its own.
COPY --from=source /python /tmp/python
RUN python3 -m venv runtime/python && runtime/python/bin/pip install -q --no-cache-dir --disable-pip-version-check \
      "/tmp/python[crypto]" && rm -rf /tmp/python
COPY --from=java /build/driver/server/target/lws-client-driver.jar driver/
COPY --from=java /build/driver/adapters/java/target/lws-driver-adapter-java.jar driver/adapters/java/
# The JavaScript adapter imports ../../../js/dist, the PHP one loads ../../../php/src.
COPY --from=source /driver/adapters/js/adapter.mjs driver/adapters/js/
COPY --from=js /build/js/package.json js/
COPY --from=js /build/js/dist js/dist
COPY --from=source /driver/adapters/python/adapter.py driver/adapters/python/
COPY --from=go /out/lws-driver-adapter-go driver/adapters/go/
COPY --from=rust /out/lws-driver-adapter-rust driver/adapters/rust/
COPY --from=rust /out/lws-driver-adapter-wasm /out/lws_client.wasm driver/adapters/wasm/
COPY --from=cpp /out/lws-driver-adapter-cpp driver/adapters/cpp/
COPY --from=csharp /out/lws-driver-adapter-csharp driver/adapters/csharp/
COPY --from=swift /out/lws-driver-adapter-swift driver/adapters/swift/
COPY --from=source /driver/adapters/php/adapter.php driver/adapters/php/
COPY --from=source /php/src php/src
COPY --from=kotlin /build/driver/adapters/kotlin/build/libs/lws-driver-adapter-kotlin.jar driver/adapters/kotlin/
COPY application.yaml driver/config/application.yaml
COPY --chmod=755 entrypoint.sh /usr/local/bin/lws-clients
RUN echo "$LWS_CLIENT_REF" > VERSION
WORKDIR /opt/lws-client/driver
USER lws
EXPOSE 18095
ENTRYPOINT ["/usr/local/bin/lws-clients"]
