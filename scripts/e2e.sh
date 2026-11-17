#!/usr/bin/env bash
# End-to-end check: generate a project, build it with Maven, start it, and
# probe the actuator health endpoint. Requires JDK 25 and network access on the
# first run (Maven downloads the wrapper distribution and dependencies).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="$root/bin/spring-gen"
port="${E2E_PORT:-18080}"
work="$(mktemp -d)"
pid=""

cleanup() {
	if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
	fi
	rm -rf "$work"
}
trap cleanup EXIT

if [[ ! -x "$bin" ]]; then
	echo "e2e: $bin not found; run 'make build' first" >&2
	exit 1
fi

echo "e2e: generating project in $work"
"$bin" new e2e-demo \
	--group com.example \
	--description "E2E demo" \
	--output "$work" \
	--no-interactive

project="$work/e2e-demo"

echo "e2e: building and testing the generated project"
(cd "$project" && ./mvnw -q -B verify)

jar="$(find "$project/target" -maxdepth 1 -name '*.jar' | head -n 1)"
if [[ -z "$jar" ]]; then
	echo "e2e: no jar produced in $project/target" >&2
	exit 1
fi

echo "e2e: starting $(basename "$jar") on port $port"
java -jar "$jar" --server.port="$port" >"$work/app.log" 2>&1 &
pid=$!

for _ in $(seq 1 90); do
	if curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
		echo "e2e: passed — health endpoint reports UP"
		exit 0
	fi
	if ! kill -0 "$pid" 2>/dev/null; then
		echo "e2e: application exited before becoming healthy" >&2
		cat "$work/app.log" >&2
		exit 1
	fi
	sleep 1
done

echo "e2e: timed out waiting for the health endpoint" >&2
cat "$work/app.log" >&2
exit 1
