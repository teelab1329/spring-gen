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
| `--request-id-check` | Redis-backed duplicate request id detection | false / prompt |
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
| `logging` | `common.logging` |

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
| `logging` | `infrastructure.logging` |

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
- blank `name` → `ValidationException` → `400 {"status":"fail","message":"Name must not be blank."}`
- missing parameter → `400 {"status":"fail","message":"Missing required parameter 'name'."}`
- wrong parameter type → `400 {"status":"fail","message":"Invalid value for parameter 'page'."}`
- `BusinessException(status, message[, data])` → fail with the caller's status:
  `409 {"status":"fail","message":"…","data":{…}}`,
  `404 {"status":"fail","message":"…"}` when data is null
- unknown path → `404 {"status":"fail","message":"Resource not found: /api/nope"}`
- unexpected exception → `500 {"status":"error","message":"Internal server error"}`

`message` is always the human-readable text. `data` holds business information
only, so request-level failures omit it entirely; `BusinessException` is the one
way to attach data to a fail. Messages are full sentences (`Name must not be
blank.`), and `ValidationException` takes only the message.

`BusinessException` takes an `HttpStatus` and the handler answers with it. The
status is always explicit (no hidden default) and must be a 4xx: JSend `fail`
means the client can fix the request, while 5xx belongs to `error`. The
constructor rejects anything else with `IllegalArgumentException`.

```java
throw new BusinessException(HttpStatus.CONFLICT, "Item SKU-1 is out of stock.", Map.of("sku", "SKU-1"));
throw new BusinessException(HttpStatus.NOT_FOUND, "Customer 42 does not exist.");
```

Trade-off accepted: in the `clean` architecture the exception lives in
`domain.exception`, so importing `HttpStatus` leaks a web type into the domain.
Keeping the status choice at the call site was worth it; a transport-neutral
status code was considered and rejected.

This deviates from the JSend specification, which puts fail details in `data`
and defines no `message` for `fail`. The deviation is intentional: keep the
human text in `message`, and treat `data` as business payload. Do not "fix" it
back without asking.

Controllers return `ResponseEntity<JSendResponse<…>>` explicitly. A global
`ResponseBodyAdvice` wrapper was rejected because it would also wrap
`/actuator/health` and plain-string responses. `ValidationException` and
`BusinessException` belong to the always-generated JSend code, not to the
sample, so projects without the sample still compile.

## 4.2 Logging and trace correlation

Tracing exists **only** to annotate logs; nothing is exported.

- `pom.xml` adds `io.micrometer:micrometer-tracing-bridge-otel` (version managed
  by Boot). No `opentelemetry-exporter-*` dependency is generated, and a Go test
  fails if one appears.
- `management.tracing.sampling.probability: 1.0`, because a sampled-out request
  would otherwise log no ids. Without an exporter the cost is negligible.
- Log output stays plain text; Spring Boot's default correlation pattern already
  prints `[traceId-spanId]`. An incoming W3C `traceparent` header is continued,
  so the caller's trace id shows up in the logs.

HTTP logging is always generated and toggled by property:

```yaml
app:
  http-logging:
    enabled: true
    max-body-size: 10KB
    exclude-paths: [/actuator/**]
  logging:
    masking:
      enabled: true
      rules:
        - { type: FULL,    keys: [password, token, authorization, cookie, x-api-key] }
        - { type: PARTIAL, keys: [cardNumber, pan], keep-start: 4, keep-end: 4 }
        - { type: EMAIL,   keys: [email, emailAddress] }
        - { type: HASH,    keys: [nationalId, phone] }
```

`HttpLoggingFilter` logs after the request completes:

```
[HTTP-REQUEST] method : GET, uri : /api/hello?name=Tee, headers : {accept=*/*, authorization=***}, body : 
[HTTP-RESPONSE] method : GET, uri : /api/hello?name=Tee, status : 200, duration : 5ms, headers : {content-type=application/json}, body : {"status":"success",…}
```

### Global masking

Masking is not done by the logging code; it happens in Logback, so it covers
every log line, including framework and library output:

- `logback-spring.xml` redefines `%m`/`%msg`/`%message` to `MaskingMessageConverter`
  and registers `%maskedEx` (`MaskingThrowableConverter`), then includes Spring
  Boot's `base.xml` unchanged, keeping the console pattern and
  `[traceId-spanId]` prefix.
- `logging.exception-conversion-word: "%maskedEx"` routes stack traces through
  the masking converter. Both hooks are required; removing either silently
  disables masking, and `make e2e` fails when that happens.
- Logback constructs converters outside Spring, so `MaskingConfig` pushes the
  rules into the static `LogMasker`. Lines logged before startup uses
  `MaskingRule.defaults()`, which mirrors the YAML defaults.
- Rules apply to key/value pairs in every shape:
  `key=value`, `key: value`, `key:value`, JSON strings and numbers, header maps,
  query strings, and XML elements. Keys are matched case-insensitively as whole
  keys (`tokenizer=` is untouched).
- Types: `FULL` → `***`; `PARTIAL` → keeps `keep-start`/`keep-end`, falls back
  to `***` when the value is too short; `EMAIL` → `j*******@example.com`, falls
  back to `***` when invalid; `HASH` → `sha256:<16 hex>`.
- **HASH is plain SHA-256.** Short or guessable values (phone numbers, national
  IDs) can be brute-forced. Use it for correlation, not secrecy.
- Header masking is no longer part of `HttpLoggingProperties`: headers are
  logged raw and the global masker redacts the configured keys.

### Logging rule (applies to all generated code)

Format: `[TYPE] <optional message> key : value, key : value`.

- Tags live in `LogType` (role `logging`) and nowhere else:
  `HTTP-REQUEST`, `HTTP-RESPONSE`, `BUSINESS-ERROR`, `SYSTEM-ERROR`,
  `RESTCLIENT-REQUEST`, `RESTCLIENT-RESPONSE`. Add a tag to the rule (this file
  and `AGENTS.md`) before using it.
- Keys are short and lowercase: `method`, `uri`, `status`, `duration`,
  `headers`, `body`, `data`, `error`. No dotted OpenTelemetry-style names.
- `uri` is path + query for incoming requests and the full URL for outgoing
  calls. `duration` renders as `5ms`. `headers` renders as `{name=value, …}`.
- Use SLF4J `{}` placeholders; never concatenate values into the message.
- Trace ids never appear in the message; Spring Boot prepends
  `[traceId-spanId]`.
- Levels: HTTP and REST client lines are INFO, `BUSINESS-ERROR` is WARN with no
  stack trace, `SYSTEM-ERROR` is ERROR with the stack trace.
- A Go test (`logrule_test.go`) fails if a template logs without a `LogType`
  tag or if `LogType` misses a tag from the rule. Framework and library logs are
  out of scope.

`GlobalExceptionHandler` logs `BUSINESS-ERROR` for `BusinessException` (status
and data, no stack trace) and `SYSTEM-ERROR` for anything unexpected (method,
uri, exception, with stack trace). Validation, missing-parameter, and 404
failures get no extra line; their `[HTTP-RESPONSE]` line already carries the
status.

`RestClientLoggingInterceptor` logs `[RESTCLIENT-REQUEST]` and
`[RESTCLIENT-RESPONSE]` and is attached through Spring Boot's
`RestClientCustomizer` and `RestTemplateCustomizer`, so clients built from the
provided builders are covered. Clients created by hand (`RestClient.create()`,
`new RestTemplate()`) bypass it. To log a response body the interceptor buffers
it and hands the caller an identical copy; binary bodies are logged by size.

Other HTTP logging details:

- Masked headers (case-insensitive) render as `***`.
- Text, JSON, XML and form bodies are logged; anything else renders as
  `[binary N bytes]`. Responses over `max-body-size` are cut with
  `…[truncated, N bytes]`; request bodies stop at the limit and say
  `…[truncated at N bytes]` because the total is not buffered.
- Newlines in bodies are escaped to `\n`, so one request cannot forge extra log
  lines.
- `text/event-stream` requests are not buffered or logged.
- The filter is registered at `Ordered.HIGHEST_PRECEDENCE + 10`. Spring Boot's
  tracing filter is at `+1`; a lower order places the logger outside it and every
  line loses `[traceId-spanId]`. Do not change the order without an e2e check.
- The response body is copied back in a `finally` block; dropping that call
  returns empty responses to clients.

## 4.3 REST clients

`httpclient5` (version from Boot's BOM) plus a generated `RestClientFactory`
give every downstream service its own connection pool. Settings live under
`app.rest-clients`:

| Setting | Default | Why |
| --- | --- | --- |
| `connect-timeout` | 2s | fail fast on dead hosts |
| `read-timeout` | 5s | per-client override for slow partners |
| `connection-request-timeout` | 1s | library default is 3 minutes — a silent stall |
| `max-connections` | 50 | library default 25 |
| `max-connections-per-route` | 20 | library default 5, a common bottleneck |
| `time-to-live` | 5m | recycle connections after DNS/LB changes |
| `validate-after-inactivity` | 2s | re-check stale connections |
| `evict-idle-after` | 30s | background close of idle connections |

`clients.<name>` overrides any value and requires `base-url`. Invalid settings
fail startup with the property name; `max-connections-per-route` may not exceed
`max-connections`.

Deliberate choices:

- Automatic retries are disabled (`disableAutomaticRetries`) — no silent repeat
  of a POST.
- Redirects are not followed; a 3xx comes back to the caller.
- Clients are built from `RestClient.Builder#clone()` (Boot's provided builder),
  so trace propagation and `RestClientLoggingInterceptor` keep working. Building
  with `RestClient.create()` bypasses both.
- `RestClientFactory.get(name)` validates the name and throws with the list of
  configured clients.
- Pool metrics are registered as `httpcomponents.httpclient.pool.*` with an
  `httpclient=<name>` tag. The actuator exposure stays `health` only; add
  `metrics` to `management.endpoints.web.exposure.include` to see them.
- Pools close on context shutdown.

Sample (with `--sample`): `GET /api/hello/remote` calls this same service through
the `greeting` client, producing `[RESTCLIENT-REQUEST]`/`[RESTCLIENT-RESPONSE]`
lines and an inner `[HTTP-REQUEST]` sharing the trace id.

## 4.4 Request signing

Every generated service validates three headers on `include-paths` (`/api/**`
by default):

```
X-Request-Id:        1–64 chars matching [A-Za-z0-9-]
X-Request-Timestamp: epoch milliseconds
X-Request-Signature: Base64(HMAC-SHA256(secret, requestId + timestamp + Base64(rawBody)))
```

`Base64("")` is empty, so a bodyless GET is signed over
`requestId + timestamp`.

Checks run in this order:

1. headers present and well-formed → `401`
2. timestamp within `max-clock-skew` (default `5m`, both directions) → `401`
3. signature compared in constant time → `401`
4. duplicate request id (optional) → `409`; store unreachable → `503`

Order matters: the id is stored only after the request is proven authentic, so
unauthenticated traffic cannot burn a real client's id. The filter writes JSend
failures itself (an advice cannot handle filter errors) and logs
`[SECURITY-ERROR] <message> requestId : …, uri : …`.

```yaml
app:
  security:
    request-signature:
      enabled: true
      secret: ${REQUEST_SIGNING_SECRET:}   # startup fails when enabled and empty
      max-clock-skew: 5m
      max-body-size: 1MB                   # larger bodies → 413
      include-paths: [/api/**]
      exclude-paths: [/actuator/**]
      request-id-check:                    # only generated with --request-id-check
        enabled: true
        key-prefix: "req-id:"
```

- `RequestSignatureFilter` is registered at `Ordered.HIGHEST_PRECEDENCE + 20`,
  inside the HTTP logging filter (`+10`), so rejected requests are logged and
  keep their trace id. Do not move it outside.
- The body is read once into `CachedBodyRequest` and replayed, so the controller
  sees exactly the signed bytes.
- Outgoing calls from `RestClientFactory` get `sign-requests: true`; the
  interceptor generates and signs a fresh id per call. The sample `greeting`
  client sets it, because it calls this service's own signed endpoint.
- `RequestSignerTest` pins a known-answer signature so client teams can check
  their implementation.

Known limitations:

- The signed string omits the HTTP method and path, so within the clock window a
  captured request could be replayed against a different endpoint with the same
  body. The duplicate-id check closes that; without it only the time window
  protects.
- One shared secret; there is no per-client key or key id.
- `application/x-www-form-urlencoded` POSTs are not supported: Tomcat consumes
  form parameters from the original stream, bypassing the cached copy.

With `--request-id-check`: `spring-boot-starter-data-redis` is added,
`RedisRequestIdStore` uses `SET NX` with a TTL of twice `max-clock-skew`, and
`management.health.redis.enabled` is turned off with a TODO so Redis cannot fail
probes before you decide. TODOs in the store cover Redis topology, key prefix and
outage policy.

## 5. Template layout

```
internal/assets/
  embed.go                    // //go:embed all:templates
  templates/
    base/                     pom.xml.tmpl, application.yml.tmpl, Main.java.tmpl,
                              ApplicationTests.java.tmpl, gitignore.tmpl, mvnw, mvnw.cmd,
                              mvn/wrapper/maven-wrapper.properties
    jsend/                    JSendResponse, ValidationException, BusinessException,
                              GlobalExceptionHandler (+ test)
    security/                 request signature filter, signer, cached body,
                              request id store (Redis only with the option)
    logging/                  LogType, HttpLoggingProperties, HttpLoggingConfig,
                              HttpLoggingFilter, RestClientLoggingInterceptor,
                              LogMasker + masking classes (+ tests)
    base/logback-spring.xml.tmpl   global masking hooks
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
  `./mvnw verify`, start the jar, and check health, unsigned rejection, signed
  success, stale timestamp, tampered body, JSend results, masked logs, and the
  pooled REST client. A Redis project is generated and built but not started.

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
- Request id duplicate detection is opt-in (`--request-id-check`) and adds Redis.

Open:

- **DECIDE** whether generated projects include a CI workflow (see §5). This
  decides whether templates need `[[ ]]` delimiters.
- Default branch is `main` (matches `origin/main`); never commit to it directly, see `AGENTS.md`.
