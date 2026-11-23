# Contributing

## Development setup

- JDK 21
- Git
- GraalVM Native Image only when running the native build locally

Run the test suite before opening a pull request:

```sh
./gradlew test
```

Run the service locally with `./gradlew run`. The default database is created at `./data/kurl.db`.

## Change guidelines

- Add or update tests for externally visible behavior.
- Keep persistence on direct JDBC; do not add an ORM without an architecture discussion.
- Keep URL and slug validation behavior covered by tests.
- Do not commit databases, credentials, generated binaries, or `.env` files.
- Update the API or architecture docs when behavior changes.

## Pull requests

Describe the user-visible change, test commands and results, and any deployment or migration impact. For performance claims, include the environment and a reproducible command.
