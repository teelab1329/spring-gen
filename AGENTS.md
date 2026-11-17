# spring-gen

Go CLI (Cobra) that generates Spring Boot projects from templates embedded in the binary. Design detail lives in `SPECS.md` — read it before changing generator behavior.

## Fixed decisions
- Module path: `github.com/teelab1329/spring-gen` (no `https://`).
- Generated projects: **Maven only** (`pom.xml` + wrapper). No Gradle.
- Architectures: **layered** and **clean**. Extend the architecture mapping; never duplicate templates per architecture.
- Fully offline: no network calls at generation time, never call start.spring.io.
- Input: flags first, prompt for whatever is missing and re-ask invalid answers; explicit flag values are validated but never re-prompted. Non-TTY or `--no-interactive` must fail listing every missing value, never block on stdin.

## Workflow (required, not optional)
- The default branch is `main` (also `origin`'s default). Never commit to it directly. Branch from `main` first: `feat/…`, `fix/…`, `docs/…`.
- Confirm with the user before starting work and before any action that is hard to undo (force-push, history rewrite, deleting files or branches, pushing). When scope or requirements are ambiguous, ask — do not guess.
- Run the checks in **Verification** before proposing a merge. Report what ran and the result; never claim tests pass without running them.
- Once checks pass, push the branch and open the merge request (PR) against `main` yourself, then ask the user to review. Never merge it and never enable auto-merge.
- `origin` is GitHub and `gh` is authenticated. Open PRs against `main` with `gh pr create --base main --head <branch>`; never merge them.

## Template model (the parts that bite)
- Templates are grouped **by feature** and declare a logical role (`controller`, `service`, `model`, …). `internal/generator/arch` maps role → Java package; templates ask with `{{ .Package "service" }}` and never hardcode a package or path. Asking an architecture for a role it lacks is an error.
- Templates differ per architecture only inside `{{ if eq .Architecture "clean" }}` sections; never fork a whole template.
- `pom.xml` and `application.yml` are assembled from per-feature contributions (dependency metadata in Go, YAML fragments merged). There is no "one pom per architecture".
- The base package sets both the directory layout and `package`/`import` lines; imports differ by architecture, so derive them from the same mapping.
- Name files `*.java.tmpl` so IntelliJ and Go tooling ignore them.
- Two template names are deliberately not their real names: `gitignore.tmpl` (a real `.gitignore` in `templates/` would apply to this repo) and `mvn/wrapper/…` (written as `.mvn/wrapper/…`).
- Embed with `//go:embed all:templates`: without `all:`, `.gitignore` and `.mvn/` are silently dropped.
- Embedding loses file modes: `mvnw` must be written `0755` explicitly.
- Boot/Java version defaults are single constants in Go. Templates never hardcode versions.
- Generate into a staging dir and only move it into place after validation succeeds, so failures leave nothing behind.
- The generated `HttpLoggingFilter` must stay registered at `Ordered.HIGHEST_PRECEDENCE + 10`: Spring Boot's tracing filter is at `+1`, and a lower order would put the logger outside it, dropping `[traceId-spanId]` from every line.
- Tracing is log-correlation only. Never add an `opentelemetry-exporter-*` dependency; a Go test asserts the pom has none.

## Logging
- Every generated log line follows `[TYPE] message key : value, key : value`, with tags from `LogType`: `HTTP-REQUEST`, `HTTP-RESPONSE`, `BUSINESS-ERROR`, `SYSTEM-ERROR`, `RESTCLIENT-REQUEST`, `RESTCLIENT-RESPONSE`.
- Keys are short and lowercase (`method`, `uri`, `status`, `duration`, `headers`, `body`, `data`, `error`); `uri` means path+query. Use SLF4J `{}` placeholders, never concatenation. Never put trace ids in the message — Spring Boot prefixes `[traceId-spanId]`.
- Levels: HTTP/REST client INFO, `BUSINESS-ERROR` WARN without stack trace, `SYSTEM-ERROR` ERROR with stack trace. Add a tag to `LogType` and this rule before using it; `logrule_test.go` enforces both.
- `[HTTP-REQUEST]` must be the first line of a request and `[HTTP-RESPONSE]` the last; business/security/REST client lines belong between them. The request line is emitted before `chain.doFilter`, and `BodyPeekingRequest` must keep replaying the full body to the controller.
- Masking is global, never per log call: `logback-spring.xml` redefines `%m` and registers `%maskedEx` (both are needed), and `app.logging.masking.rules` drive `LogMasker`. Do not reintroduce masking inside filters/interceptors; it would double-mask and miss other libraries.
- Outbound HTTP goes through `RestClientFactory.get(name)`, which clones Boot's `RestClient.Builder`. `RestClient.create()` or `new RestTemplate()` bypasses the pool, trace propagation, and REST client logging. Do not enable retries or redirect following without asking.

## Request signing
- `RequestSignatureFilter` runs at `HIGHEST_PRECEDENCE + 20`, inside the logging filter (`+10`). Moving it outside hides security failures from the log and drops trace ids.
- Check order is timestamp → signature → duplicate id; never store an id before the signature is verified.
- `X-Request-Signature = Base64(HMAC-SHA256(secret, requestId + timestamp + Base64(rawBody)))`. Rejections log `[SECURITY-ERROR]` and return JSend `fail`. Adding a tag means updating `LogType` and this rule.
- Outgoing clients that call a signed service set `sign-requests: true`; the interceptor signs every call.

## Cobra
- Use the vendored skill `.agents/skills/golang-spf13-cobra` (pinned in `skills-lock.json`).
- `RunE` only, and write to `cmd.OutOrStdout()` / `cmd.ErrOrStderr()` so tests capture output.

## Verification
- `make verify` — gofmt check, `go vet ./...`, `go test ./...`. `make build` writes `bin/spring-gen` with version data from git.
- Run it: `go run ./cmd/spring-gen version`. Release builds inject version data with
  `-ldflags "-X github.com/teelab1329/spring-gen/internal/version.Version=vX.Y.Z"` (also `.Commit`, `.Date`); empty values fall back to Go's embedded build metadata.
- Golden tests pin the rendered tree in `internal/generator/testdata/golden/**`. Regenerate with `make golden`, then review the diff; never hand-edit golden files.
- `make e2e` runs the full flow for **both** architectures: generate, `./mvnw verify`, start the jar, then check `/actuator/health`, unsigned rejection, signed success (openssl), stale timestamp, tampered body, JSend results, log lines and masking, the pooled REST client, and that a `--request-id-check` project builds. Run it after touching templates or the generator; it needs network on first run.
- The only JDK installed is 25, so generated projects are compiled with `--release 21` (via `<java.version>21</java.version>`). This is not proof the build works on a real JDK 21.
