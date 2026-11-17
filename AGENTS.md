# spring-gen

Go CLI (Cobra) that generates Spring Boot projects from templates embedded in the binary. Design detail lives in `SPECS.md` — read it before changing generator behavior.

## Fixed decisions
- Module path: `github.com/teelab1329/spring-gen` (no `https://`).
- Generated projects: **Maven only** (`pom.xml` + wrapper). No Gradle.
- Architectures: **layered** and **clean**. Extend the architecture mapping; never duplicate templates per architecture.
- Fully offline: no network calls at generation time, never call start.spring.io.
- Input: flags first, prompt for whatever is missing. Non-TTY or `--no-interactive` must fail with a list of missing flags, never block on stdin.

## Workflow (required, not optional)
- The default branch is `main` (also `origin`'s default). Never commit to it directly. Branch from `main` first: `feat/…`, `fix/…`, `docs/…`.
- Confirm with the user before starting work and before any action that is hard to undo (force-push, history rewrite, deleting files or branches, pushing). When scope or requirements are ambiguous, ask — do not guess.
- Run the checks in **Verification** before proposing a merge. Report what ran and the result; never claim tests pass without running them.
- Once checks pass, push the branch and open the merge request (PR) against `main` yourself, then ask the user to review. Never merge it and never enable auto-merge.
- `origin` is GitHub; `gh` and `glab` are not installed, so open the PR via the printed compare URL (`https://github.com/teelab1329/spring-gen/compare/main...<branch>`) unless the user installs a CLI.

## Template model (the parts that bite)
- Templates are grouped **by feature** and declare a logical role (`entity`, `controller`, `config`, …). Architecture code maps role + base package → output path. Templates must not contain output paths.
- `pom.xml` and `application.yml` are assembled from per-feature contributions (dependency metadata in Go, YAML fragments merged). There is no "one pom per architecture".
- The base package sets both the directory layout and `package`/`import` lines; imports differ by architecture, so derive them from the same mapping.
- Name files `*.java.tmpl` so IntelliJ and Go tooling ignore them.
- Embed with `//go:embed all:templates`: without `all:`, `.gitignore` and `.mvn/` are silently dropped.
- Embedding loses file modes: `mvnw` must be written `0755` explicitly.
- Boot/Java version defaults are single constants in Go. Templates never hardcode versions.
- Generate into a staging dir and only move it into place after validation succeeds, so failures leave nothing behind.

## Cobra
- Use the vendored skill `.agents/skills/golang-spf13-cobra` (pinned in `skills-lock.json`).
- `RunE` only, and write to `cmd.OutOrStdout()` / `cmd.ErrOrStderr()` so tests capture output.

## Verification
- `go test ./...`
- Golden tests per architecture × feature set: `go test ./... -update` rewrites `testdata/golden/**`; review that diff, never hand-edit golden files.
- Generator work is done only when a generated project builds: `./mvnw -q verify` in the output dir (Go 1.27 and JDK 25 are installed).
