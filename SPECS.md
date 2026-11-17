# SPECS — spring-gen

Status: draft, pre-implementation. Anything marked **DECIDE** is an open question.

## 1. Purpose

Generate a ready-to-build Spring Boot project from templates compiled into the
binary. The user's data (group, artifact, package, versions), the selected
architecture, and the selected tech features determine *both* the content of
each file and where it is written. No network access, no Spring Initializr.

## 2. CLI surface

```
spring-gen new [name] [flags]
spring-gen list [architectures|features]
spring-gen version
```

`new` flags:

| Flag | Meaning | Default |
| --- | --- | --- |
| `--group` | Maven groupId and Java package prefix, e.g. `com.acme` | prompt |
| `--description` | project description | `<name> service` |
| `--architecture` | `layered` or `clean` (completion provided) | prompt |
| `--virtual-threads` | enable virtual threads; disable with `--virtual-threads=false` | true / prompt |
| `--sample` | include the Hello sample endpoint; disable with `--sample=false` | true / prompt |
| `--output` | parent directory for the new project | `.` |
| `--no-interactive` | never prompt; error on missing required values | false |
| `--force` | overwrite a non-empty target directory | false |

Java `21` and Spring Boot `3.5.16` are fixed constants in
`internal/generator/generator.go`. They are not flags.

Not implemented yet (arrives with the feature registry in §3): `--artifact`,
`--package`, `--features`. For now `<name>` doubles as the artifactId, and the
package is `group + name` with dashes removed.

Boolean flags only prompt when they were not passed at all. `--virtual-threads
false` does not work for pflag bools; write `--virtual-threads=false`.

`<name>` is the project directory name and, by default, the artifactId.

Exit codes: `0` success, `1` any failure (usage, validation, generation). Errors
are printed once by `main` as `spring-gen: <message>`; Cobra's usage text is
silenced. No stack traces on user error.

`spring-gen version` (and `--version`) print one line:

```
spring-gen v0.1.0 (commit 4f2a91c3b7d1, built 2026-11-17T10:00:00Z, go1.27.3 linux/amd64)
```

Version, commit, and date come from `-ldflags -X …/internal/version.{Version,Commit,Date}`
and fall back to `debug.ReadBuildInfo` (module version, `vcs.revision`,
`vcs.time`); unset values render as `dev` / `unknown`. Commits are shortened to
12 characters.

### 2.1 Interaction rules

- Prompt only for values not supplied by flags or arguments.
- Prompts go to stderr; results and summaries go to stdout.
- Prompt order: name, group, description, architecture (numbered list; a
  number or the name is accepted), virtual threads, sample endpoint.
- An empty answer to a required question is re-asked (`  group is required`), and
  an invalid answer is re-asked with the validation message. Nothing is written
  until every value is valid.
- Flag values are validated but never re-prompted: `--group acme` fails fast,
  and `--architecture` or `--virtual-threads=false` skip their prompts.
- End of input (Ctrl-D, empty pipe) aborts with
  `aborted: no input for <field>`; no project directory is created.
- If stdin is not a TTY, or `--no-interactive` is set, list every missing value
  in one error: `missing required values: <name> argument, --group, --architecture`.
- Fully-flagged runs must be usable from CI, so keep prompt output out of stdout.

Prompt implementation: plain `bufio` line prompts in `internal/prompt`. Prompts
write to stderr, so a fully flagged run stays machine-readable. No TUI
dependency; feature selection will start as a comma-separated answer.

## 3. Inputs and validation

Validate everything before touching the filesystem.

| Field | Rule |
| --- | --- |
| `name` | `^[a-z][a-z0-9-]*$`, no path separators, not `.`/`..` |
| `group` | dotted segments, `^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)+$` |
| `artifact` | `^[a-z][a-z0-9-]*$` |
| `package` | derived: group + artifact with `-` removed; reject Java keywords as any segment |
| `architecture` | must exist in registry |
| `features` | must exist in registry; dependencies satisfied; no conflicts |
| `java` | fixed at 21 (`JavaVersion`), not user-selectable |
| target dir | must not exist, or be empty, or `--force` |

Conflict/dependency model in `internal/generator/feature/registry.go`:

```go
type Feature struct {
    ID          string
    Requires    []string        // e.g. jpa requires web
    Conflicts   []string
    Dependencies []Dependency   // Maven deps contributed to pom.xml
    Properties  []Property      // pom properties, e.g. mapstruct version
    Config      string          // YAML fragment, merged into application.yml
}
```

Initial feature set: `web`, `validation`, `jpa`, `flyway`, `security`,
`actuator`, `lombok`, `mapstruct`, `testcontainers`. Dependencies are declared
as structured data, **not** as XML text fragments — merging raw XML is fragile.

## 4. Architecture mapping

Each template declares a logical role. `internal/generator/arch` owns the role →
package mapping; templates ask for a package with `{{ .Package "service" }}` and
never hardcode one.

Layered (`layered`):

| Role | Package suffix |
| --- | --- |
| `response` | `common.response` |
| `exception` | `common.exception` |
| `handler` | `common.exception` |
| `model` | `model` |
| `service` | `service` |
| `dto` | `dto` |
| `controller` | `controller` |

Clean (`clean`):

| Role | Package suffix |
| --- | --- |
| `response` | `infrastructure.web.response` |
| `exception` | `domain.exception` |
| `handler` | `infrastructure.web` |
| `model` | `domain.model` |
| `usecase` | `application.port.in` |
| `service` | `application.service` |
| `dto` | `infrastructure.web.dto` |
| `controller` | `infrastructure.web` |

Asking an architecture for a role it does not define is an error (layered has no
`usecase`), so a template cannot silently land in the wrong package. Files land
under `src/main/java/<base path>/<suffix>/`, tests under `src/test/java/…`.

Templates contain an `{{ if eq .Architecture "clean" }}` section only where the
code genuinely differs (e.g. the controller injects `HelloUseCase` instead of
`HelloService`). Anything placed outside the Java tree (pom, wrapper, yml) is
architecture-independent.

## 4.1 JSend responses

JSend classes are always generated; the Hello sample is optional (`--sample`).

- `GET /api/hello?name=Tee` → `200 {"status":"success","data":{"message":"Hello, Tee!"}}`
- blank `name` → `ValidationException` → `400 {"status":"fail","data":{"name":"must not be blank"}}`
- missing/invalid parameter → `400` fail; unknown path → `404` fail
- unexpected exception → `500 {"status":"error","message":"Internal server error"}`

Controllers return `ResponseEntity<JSendResponse<…>>` explicitly. A global
`ResponseBodyAdvice` wrapper was rejected because it would also wrap
`/actuator/health` and plain-string responses. `ValidationException` belongs to
the always-generated JSend code, not to the sample, so projects without the
sample still compile.

## 5. Template layout

```
internal/assets/
  embed.go                    // //go:embed all:templates
  templates/
    base/                     pom.xml.tmpl, application.yml.tmpl, Main.java.tmpl,
                              ApplicationTests.java.tmpl, gitignore.tmpl, mvnw, mvnw.cmd,
                              mvn/wrapper/maven-wrapper.properties
    jsend/                    JSendResponse, ValidationException, GlobalExceptionHandler
    sample/                   Greeting, HelloUseCase (clean only), HelloService,
                              HelloResponse, HelloController, HelloControllerTest
    web/ jpa/ validation/ …   planned feature directories
```

`gitignore.tmpl` is written as `.gitignore` and `mvn/wrapper/…` as
`.mvn/wrapper/…`: storing the real names would make git ignore the wrapper jar
and apply the generated project's ignore rules to this repo.

Template data:

```go
type Data struct {
    Name, Group, Artifact, Description string
    BasePackage, BaseDir, ClassName    string
    Architecture   string // "layered" | "clean"
    VirtualThreads bool
    Sample         bool
    Java, Boot     string
}

func (d Data) Package(role string) (string, error) // role → full Java package
```

`FuncMap` provides `xml`, `lower`, `pascal`; role packages come from the
`.Package` method, which returns an error for roles the architecture does not
define. Rendering uses `Option("missingkey=error")`, so a typo fails the run
instead of emitting `<no value>`.

Delimiters stay `{{ }}` for Java/YAML/XML. Files that would conflict (GitHub
Actions `${{ }}`, e.g. CI workflow templates) would need `Delims("[[", "]]")`.
**DECIDE** whether to ship CI workflow templates at all — if not, skip this
special case.

`application.yml` is a single template with an `{{ if .VirtualThreads }}` block
for `spring.threads.virtual.enabled`. Feature-registry fragments are not
implemented yet.

### 5.1 Merged files

Stage 1 renders `pom.xml` from a single template with fixed dependencies
(web, actuator, test). Once the feature registry lands, the same template is
fed dependencies and properties from it, sorted and deduplicated; features must
never contribute raw XML text.

### 5.2 Architecture overrides

If a role's content genuinely differs per architecture (e.g. clean's
`RepositoryAdapter` is Spring Data, layered's `Repository` is the same thing
under a different name), prefer a distinct feature template keyed by role. Only
add `templates/<arch>/...` when the same role must diverge; the resolver checks
`<arch>/` first, then the feature dir.

## 6. Generation pipeline

1. **Resolve** inputs (flags → prompt → defaults).
2. **Validate** (§3). Nothing is written on failure.
3. **Plan**: for each selected template, compute destination path via the
   architecture, detect duplicate destinations (error).
4. **Render** into a staging directory inside the output parent
   (`.spring-gen-<name>-tmp`), creating dirs as needed. `mvnw` gets `0755`,
   `mvnw.cmd` stays `0644`.
5. **Commit**: rename staging → final path; `--force` removes the target first.
   Clean up staging on any error (defer).

Rendering is a pure function `Render(Data) ([]File, error)` so it can be tested
without the filesystem; each manifest entry carries its role, destination, and
an optional `when(Data)` condition.

## 7. Testing

- Unit: validation rules, package/class derivation, role → package mapping for
  both architectures, generated file set, `mvnw` mode, existing-target and
  `--force` behavior, sample on/off, virtual threads on/off.
- Golden: `internal/generator/testdata/golden/{layered-full,clean-full,layered-minimal}/`,
  regenerated with `make golden` (a `-update` flag scoped to the generator
  package). Sorted traversal, no absolute paths in output.
- Smoke: generate a project into `t.TempDir()`; assert the pom pins Boot and
  Java and that no staging directory remains.
- `make e2e` (not part of `go test`): for each architecture, generate,
  `./mvnw verify`, start the jar, and check health, JSend success, 400 fail,
  404 fail.

## 8. Package layout

```
cmd/spring-gen/main.go          // thin: cli.Execute()
internal/cli/                   // root.go, new.go, version.go (+ tests)
internal/generator/             // input/derivation, validation, render, atomic write
internal/generator/testdata/    // golden trees
internal/generator/arch/        // role → package mapping for layered and clean
internal/generator/feature/     // planned: feature registry + YAML fragments
internal/assets/                // embed.go + templates/{base,jsend,sample}
internal/prompt/                // line prompts
scripts/e2e.sh                  // end-to-end check behind `make e2e`
```

## 9. Resolved and open questions

Resolved:

- Java is fixed at 21 and Spring Boot at 3.5.16 (`internal/generator/generator.go`).
- Prompts use plain `bufio` line input in `internal/prompt`; no TUI dependency.
- `new` does not run `git init` in the generated project.
- Architectures are `layered` and `clean`, resolved by `internal/generator/arch`.
- Virtual threads default to on; the sample endpoint defaults to on.

Open:

- **DECIDE** whether generated projects include a CI workflow (see §5). This
  decides whether templates need `[[ ]]` delimiters.
- Default branch is `main` (matches `origin/main`); never commit to it directly, see `AGENTS.md`.
