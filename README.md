# spring-gen

Generate a runnable Spring Boot service from templates embedded in a single Go
binary. No network calls, no Spring Initializr: pick an architecture, answer a
few questions, and get a project that builds.

```
spring-gen new order-service --group com.acme --architecture layered
```

## Install

Linux, macOS, or Windows (Git Bash / WSL):

```sh
curl -fsSL https://raw.githubusercontent.com/teelab1329/spring-gen/main/scripts/install.sh | sh
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/teelab1329/spring-gen/main/scripts/install.ps1 | iex
```

Specific version or directory:

```sh
curl -fsSL .../scripts/install.sh | sh -s -- --version v1.0.0 --dir /usr/local/bin
```

With Go:

```sh
go install github.com/teelab1329/spring-gen/cmd/spring-gen@v1.0.0
```

The install scripts download the archive for your OS/CPU, verify it against the
release `checksums.txt`, and print the installed version.

## Usage

```
spring-gen new [name] [flags]
spring-gen version
```

`new` prompts for anything you leave out. Flags make it scriptable:

| Flag | Meaning | Default |
| --- | --- | --- |
| `--group` | Maven groupId and Java package prefix | prompt |
| `--description` | project description | `<name> service` |
| `--architecture` | `layered` or `clean` | prompt |
| `--virtual-threads` | enable `spring.threads.virtual.enabled` (disable with `=false`) | true |
| `--sample` | include the Hello sample endpoint (disable with `=false`) | true |
| `--request-id-check` | Redis-backed duplicate request id detection | false |
| `--output` | directory that will contain the project | `.` |
| `--no-interactive` | fail instead of prompting; lists every missing value | false |
| `--force` | replace a non-empty target directory | false |

Generated projects target **Java 21** and **Spring Boot 3.5.16** (fixed
constants), use Maven with the wrapper, and never call the network at generation
time.

## What a generated service contains

- **Architecture-aware layout.** `layered` (controller/service/dto/model) or
  `clean` (domain/application/infrastructure) — same templates, different
  packages, chosen by role.
- **JSend responses.** `{status, data, message}` with `success`/`fail`/`error`;
  readable text always in `message`, business payload in `data`.
- **HTTP logging and trace correlation.** One `[HTTP-REQUEST]` line, business
  logs, then `[HTTP-RESPONSE]`, all prefixed with `[traceId-spanId]` from the
  OpenTelemetry bridge (log correlation only, no exporter).
- **Global masking.** `FULL`, `PARTIAL`, `EMAIL` and `HASH` rules applied to
  every log line and stack trace, including framework output.
- **Pooled REST clients.** One Apache HttpClient 5 connection pool per
  downstream service, with timeouts, size limits and metrics.
- **Signed requests.** `X-Request-Id`, `X-Request-Timestamp` and
  `X-Request-Signature` (HMAC-SHA256), validated in that order, with relative
  timestamps and optional Redis duplicate detection.
- **Tests included.** Context load, JSend sample, logging filter, masking,
  pool wiring, signature rejection paths, and the exception advice.

## Development

```sh
make verify     # gofmt check, go vet, go test
make golden     # regenerate golden project trees, then review the diff
make e2e        # generate, build, start, and exercise a project (needs JDK + network)
make build      # bin/spring-gen with version data from git
make snapshot   # local GoReleaser build in dist/ (all OS/arch)
```

`SPECS.md` documents the design and the client-facing rules (signing, logging,
masking); `AGENTS.md` holds the conventions an agent needs to work here.

## License

MIT
