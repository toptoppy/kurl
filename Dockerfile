FROM gcr.io/distroless/cc-debian12:nonroot
WORKDIR /app
COPY --chown=65532:65532 build/native/nativeCompile/kurl /app/kurl
EXPOSE 8080
ENV PORT=8080
ENV DATABASE_PATH=/app/data/kurl.db
HEALTHCHECK --interval=10s --timeout=3s --start-period=2s --retries=3 CMD ["/app/kurl", "healthcheck"]
ENTRYPOINT ["/app/kurl"]
