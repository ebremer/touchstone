# syntax=docker/dockerfile:1
# sparq-lws-core (github.com/sparq-org/sparq, crates/sparq-lws-core) in its W3C LWS 1.0 mode as a target server:
# one storage at the root, with its embedded authorization server. The LWS mode is on sparq's main branch (PR #6650
# and the PRs it was split into, merged by 11 October 2026). See ../README.md for the contract and entrypoint.sh for
# the settings.
#
# The source is the repository at SPARQ_REF. To build a local checkout instead:
#   docker build --build-context source=/path/to/sparq targetservers/sparq-lws

ARG SPARQ_REPO=https://github.com/sparq-org/sparq.git
ARG SPARQ_REF=01b5ad8cf687dd9329a3f4301e8a314fa0738cc5

# ---- source ----
FROM alpine/git:2.52.0 AS clone
ARG SPARQ_REPO
ARG SPARQ_REF
RUN git init -q /src && git -C /src remote add origin "$SPARQ_REPO" \
 && git -C /src fetch -q --depth 1 origin "$SPARQ_REF" && git -C /src checkout -q FETCH_HEAD && rm -rf /src/.git

FROM scratch AS source
COPY --from=clone /src/ /

# ---- build ----
# The toolchain rust-toolchain.toml pins; RUSTUP_TOOLCHAIN keeps rustup from fetching the components and the
# wasm target that file lists for sparq's CI.
FROM rust:1.97.1-slim-trixie AS build
RUN apt-get update && apt-get install -y --no-install-recommends cmake g++ make perl pkg-config \
 && rm -rf /var/lib/apt/lists/*
ENV RUSTUP_TOOLCHAIN=1.97.1
WORKDIR /build
COPY --from=source / .
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/build/target \
    cargo build --release --locked -p sparq-lws-core \
 && cp target/release/sparq-lws-core /sparq-lws-core

# ---- runtime ----
FROM debian:trixie-slim
ARG SPARQ_REF
LABEL org.opencontainers.image.title="sparq-lws-core (LWS target server)" \
      org.opencontainers.image.source="https://github.com/sparq-org/sparq" \
      org.opencontainers.image.revision="${SPARQ_REF}"
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 sparq \
 && useradd --system --uid 10001 --gid sparq --home-dir /data --no-create-home sparq \
 && mkdir -p /data && chown sparq:sparq /data
COPY --from=build /sparq-lws-core /usr/local/bin/sparq-lws-core
COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
# The storage is in memory, so every restart empties it. /data keeps the keys: as-key.json signs access tokens,
# notify-key.json signs webhook deliveries (each made at the first start).
WORKDIR /data
VOLUME /data
USER sparq
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
