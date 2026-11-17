# Changelog

## 1.0.0 - 2026-11-18

First stable release.

### Generator

- `spring-gen new` generates a Maven Spring Boot 3.5.16 project targeting Java 21.
- Architectures: `layered` and `clean`, resolved by a role → package mapping.
- Interactive prompts for missing values with re-asking on invalid input; fully
  flagged runs work in CI, and non-interactive runs list every missing value.
- Atomic generation: renders into a staging directory and only renames it into
  place, so failures leave nothing behind.
- Optional virtual threads and an optional Hello sample endpoint.
- Optional request id duplicate detection with Redis.
- Golden, unit, and end-to-end tests; `make e2e` builds and runs a generated
  service in both architectures.

### Generated service

- JSend responses with readable `message` and business-only `data`.
- OpenTelemetry trace correlation for plain-text logs (no exporter).
- Typed log lines (`[HTTP-REQUEST]`, `[HTTP-RESPONSE]`, `[BUSINESS-ERROR]`,
  `[SYSTEM-ERROR]`, `[RESTCLIENT-*]`, `[SECURITY-ERROR]`) in request → business
  → response order.
- Global masking with `FULL`, `PARTIAL`, `EMAIL` and `HASH` types across
  `key=value`, `key: value`, JSON, XML, header maps and query strings.
- Pooled Apache HttpClient 5 REST clients, one pool per downstream service.
- Request signing with `X-Request-Id`, `X-Request-Timestamp` and
  `X-Request-Signature` (HMAC-SHA256), plus optional Redis replay protection.

### Distribution

- Cross-platform release archives for Linux, macOS and Windows (amd64, arm64)
  with SHA-256 checksums.
- `scripts/install.sh` (Linux, macOS, Git Bash) and `scripts/install.ps1`
  (Windows PowerShell).
- CI: `make verify`, `make e2e` on JDK 21, script linting, and an install test on
  Ubuntu, macOS and Windows after each release.
