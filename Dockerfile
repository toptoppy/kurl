FROM scratch AS native-binary
COPY build/native/nativeCompile/kurl /kurl

FROM debian:bookworm-slim AS native-runtime-dependencies

FROM gcr.io/distroless/cc-debian12:nonroot
WORKDIR /app
COPY --chown=65532:65532 docker/data /app/data
COPY --from=native-runtime-dependencies /lib/x86_64-linux-gnu/libz.so.1* /lib/x86_64-linux-gnu/
COPY --from=native-binary --chown=65532:65532 /kurl /app/kurl
EXPOSE 8080
ENV PORT=8080
ENV DATABASE_PATH=/app/data/kurl.db
HEALTHCHECK --interval=10s --timeout=3s --start-period=2s --retries=3 CMD ["/app/kurl", "healthcheck"]
ENTRYPOINT ["/app/kurl"]
