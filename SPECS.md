# SPECS — spring-gen

Status: draft, pre-implementation. Anything marked **DECIDE** is an open question.

## 1. Purpose

Generate a ready-to-build Spring Boot project from templates compiled into the
binary. The user's data (group, artifact, package, versions), the selected
architecture, and the selected tech features determine *both* the content of
each file and where it is written. No network access, no Spring Initializr.

## 2. CLI surface

```
spring-gen new <name> [flags]
spring-gen list [architectures|features]
spring-gen version
```

`new` flags:

| Flag | Meaning | Default |
| --- | --- | --- |
| `--group` | Maven groupId, e.g. `com.acme` | prompt |
| `--artifact` | Maven artifactId / Go-style name | from `<name>` |
| `--package` | base Java package | `{group}.{artifact}` sanitized |
| `--architecture` | `layered` \| `clean` | prompt |
| `--features` | comma list, e.g. `web,jpa,validation` | prompt (multi-select) |
| `--java` | Java release version | `25` |
| `--boot` | Spring Boot version | pinned constant |
| `--output` | parent directory for the new project | `.` |
| `--no-interactive` | never prompt; error on missing required values | false |
| `--force` | overwrite a non-empty target directory | false |

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

- Prompt only for values not supplied by flags.
- Prompts go to stderr; results and summaries go to stdout.
- If stdin is not a TTY, or `--no-interactive` is set, treat every unanswered
  required value as an error: `missing required flags: --group --architecture`.
- Fully-flagged runs must be usable from CI, so keep prompt output out of stdout.

Prompt implementation: plain `bufio.Reader` line prompts in `internal/prompt`.
No TUI dependency until the plain version is proven insufficient. **DECIDE** if
multi-select features should use `charmbracelet/huh` from the start.

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
| `java` | >= 21 |
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

Each template declares a logical role. The architecture resolves role → path.

```go
type Architecture interface {
    ID() string
    Path(role Role, base string) (string, error) // returns path relative to project root
    Package(role Role, base string) string       // Java package for the role
}
```

Layered (`layered`):

| Role | Path |
| --- | --- |
| `entity` | `src/main/java/<base>/entity/<Name>.java` |
| `repository` | `src/main/java/<base>/repository/` |
| `service` | `src/main/java/<base>/service/` |
| `controller` | `src/main/java/<base>/controller/` |
| `dto` | `src/main/java/<base>/dto/` |
| `config` | `src/main/java/<base>/config/` |

Clean (`clean`):

| Role | Path |
| --- | --- |
| `entity` | `src/main/java/<base>/domain/model/` |
| `repository` (port) | `src/main/java/<base>/domain/port/` |
| `usecase` | `src/main/java/<base>/application/usecase/` |
| `repository` (adapter) | `src/main/java/<base>/infrastructure/persistence/` |
| `controller` | `src/main/java/<base>/infrastructure/web/` |
| `dto` | `src/main/java/<base>/application/dto/` |
| `config` | `src/main/java/<base>/infrastructure/config/` |

Roles that exist twice in one architecture (clean's port vs adapter) get
suffixed template names (`RepositoryPort.java.tmpl`, `RepositoryAdapter.java.tmpl`)
mapped to the same role with distinct keys. Templates may include an
architecture conditional section for imports, but the file must remain a single
template per role per architecture-neutral feature.

Anything placed outside `src/main/java` (pom, wrapper, yml, migrations) is
architecture-independent: role `root`, empty package.

## 5. Template layout

```
internal/assets/
  embed.go                    // //go:embed all:templates
  templates/
    base/                     pom.xml.tmpl, application.yml.tmpl, Main.java.tmpl,
                              README.md.tmpl, .gitignore, .mvn/…, mvnw, mvnw.cmd, .gitattributes
    web/                      Controller.java.tmpl
    jpa/                      Entity.java.tmpl, RepositoryPort.java.tmpl,
                              RepositoryAdapter.java.tmpl, V1__init.sql, config fragment
    validation/ security/ actuator/ lombok/ mapstruct/ testcontainers/
    layered/                  optional architecture-specific overrides (see §5.2)
    clean/
```

Template data:

```go
type Data struct {
    Name, Group, Artifact, Package, BaseDir string
    Arch        string
    Features    []string          // sorted, for conditionals
    Java, Boot  string
    Deps        []Dependency
    Props       map[string]string
    Has         func(string) bool // feature predicate for templates
}
```

`FuncMap` provides `has`, `pkg` (package for a role), `camel`, `pascal`,
`kebab`, `snake`, `lower`. Unknown template functions must fail the render, not
emit `<no value>`; render with `Option("missingkey=error")`.

Delimiters stay `{{ }}` for Java/YAML/XML. Files that would conflict (GitHub
Actions `${{ }}`, e.g. CI workflow templates) are rendered with
`Delims("[[", "]]")`. **DECIDE** whether to ship CI workflow templates at all —
if not, skip this special case.

### 5.1 Merged files

- `pom.xml.tmpl`: one template; dependencies and properties come from the
  feature registry. Sorted, deduplicated.
- `application.yml`: base template plus each feature's `Config` fragment, deep
  merged with `gopkg.in/yaml.v3` (maps merge, scalars override in feature order,
  lists union without duplicates). Fragments are data files, not Go strings.

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

Rendering is a pure function `Render(Data, []PlannedFile) (map[string][]byte, error)`
so it can be tested without the filesystem.

## 7. Testing

- Unit: validation rules, each architecture's `Path`/`Package`, YAML merge,
  feature dependency resolution.
- Golden: `testdata/golden/<arch>-<feature-hash>/` trees, compared with
  `-update` to regenerate. Sorted traversal, no absolute paths in output.
- Smoke: generate a project into `t.TempDir()`; assert pom contains contributed
  deps and that no staging dir remains.
- Build check (manual or CI, not `go test`): `./mvnw -q verify` inside a
  generated project. Real Maven runs are too slow to gate every test.

## 8. Package layout

```
cmd/spring-gen/main.go          // thin: cli.Execute()
internal/cli/                   // root.go, new.go, list.go, version.go
internal/generator/             // pipeline, plan, render
internal/generator/arch/        // layered.go, clean.go, registry
internal/generator/feature/     // registry + YAML fragments
internal/assets/                // embed.go + templates/
internal/prompt/                // line prompts
testdata/golden/
```

## 9. Open questions

- Default branch is `main` (matches `origin/main`); never commit to it directly, see `AGENTS.md`.
- **DECIDE** initial Spring Boot version constant; verify the chosen version's
  Maven artifacts exist before pinning.
- **DECIDE** whether generated projects include a CI workflow (see §5).
- **DECIDE** multi-select prompt library (see §2.1).
- **DECIDE** whether `spring-gen new` also runs `git init` in the output
  (default: no).
