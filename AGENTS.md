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
- `origin` is GitHub. `gh` is installed but not authenticated; until `gh auth login` is run, open PRs via the printed compare URL (`https://github.com/teelab1329/spring-gen/compare/main...<branch>`).

## Template model (the parts that bite)
- Templates are grouped **by feature** and declare a logical role (`entity`, `controller`, `config`, …). Architecture code maps role + base package → output path. Templates must not contain output paths.
- `pom.xml` and `application.yml` are assembled from per-feature contributions (dependency metadata in Go, YAML fragments merged). There is no "one pom per architecture".
- The base package sets both the directory layout and `package`/`import` lines; imports differ by architecture, so derive them from the same mapping.
- Name files `*.java.tmpl` so IntelliJ and Go tooling ignore them.
- Two template names are deliberately not their real names: `gitignore.tmpl` (a real `.gitignore` in `templates/` would apply to this repo) and `mvn/wrapper/…` (written as `.mvn/wrapper/…`).
- Embed with `//go:embed all:templates`: without `all:`, `.gitignore` and `.mvn/` are silently dropped.
- Embedding loses file modes: `mvnw` must be written `0755` explicitly.
- Boot/Java version defaults are single constants in Go. Templates never hardcode versions.
- Generate into a staging dir and only move it into place after validation succeeds, so failures leave nothing behind.

## Cobra
- Use the vendored skill `.agents/skills/golang-spf13-cobra` (pinned in `skills-lock.json`).
- `RunE` only, and write to `cmd.OutOrStdout()` / `cmd.ErrOrStderr()` so tests capture output.

## Verification
- `make verify` — gofmt check, `go vet ./...`, `go test ./...`. `make build` writes `bin/spring-gen` with version data from git.
- Run it: `go run ./cmd/spring-gen version`. Release builds inject version data with
  `-ldflags "-X github.com/teelab1329/spring-gen/internal/version.Version=vX.Y.Z"` (also `.Commit`, `.Date`); empty values fall back to Go's embedded build metadata.
- Golden tests pin the rendered tree in `internal/generator/testdata/golden/**`. Regenerate with `make golden`, then review the diff; never hand-edit golden files.
- `make e2e` generates a project, runs `./mvnw verify` on it, starts the jar, and requires `/actuator/health` to return `UP`. Run it after touching templates or the generator; it needs network on first run.
- The only JDK installed is 25, so generated projects are compiled with `--release 21` (via `<java.version>21</java.version>`). This is not proof the build works on a real JDK 21.
