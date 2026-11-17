#!/usr/bin/env bash
# End-to-end check: generate a project per architecture, build it with Maven,
# start it, and exercise the JSend sample endpoint. Requires JDK 25 and network
# access on the first run (Maven downloads the wrapper distribution and deps).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="$root/bin/spring-gen"
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

stop_app() {
	if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
	fi
	pid=""
}

start_app() {
	local jar="$1" port="$2"
	java -jar "$jar" --server.port="$port" >"$work/app-$port.log" 2>&1 &
	pid=$!

	for _ in $(seq 1 90); do
		if curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
			return 0
		fi
		if ! kill -0 "$pid" 2>/dev/null; then
			echo "e2e: application exited before becoming healthy" >&2
			cat "$work/app-$port.log" >&2
			return 1
		fi
		sleep 1
	done

	echo "e2e: timed out waiting for the health endpoint" >&2
	cat "$work/app-$port.log" >&2
	return 1
}

# check_response <label> <url> <expected status> <expected body fragment> [forbidden fragment]
check_response() {	local label="$1" url="$2" want_status="$3" want_body="$4" forbidden="${5:-}"
	local response status body

	response="$(curl -s -w '\n%{http_code}' "$url")"
	status="${response##*$'\n'}"
	body="${response%$'\n'*}"

	if [[ "$status" != "$want_status" ]]; then
		echo "e2e: $label: status $status, want $want_status (body: $body)" >&2
		return 1
	fi
	if [[ "$body" != *"$want_body"* ]]; then
		echo "e2e: $label: body '$body' does not contain '$want_body'" >&2
		return 1
	fi
	if [[ -n "$forbidden" && "$body" == *"$forbidden"* ]]; then
		echo "e2e: $label: body '$body' must not contain '$forbidden'" >&2
		return 1
	fi
	echo "e2e: $label ok"
}

# check_log <label> <logfile> <extended regex>
check_log() {
	local label="$1" logfile="$2" pattern="$3"
	if ! grep -qE "$pattern" "$logfile"; then
		echo "e2e: $label: no log line matching '$pattern'" >&2
		tail -n 30 "$logfile" >&2
		return 1
	fi
	echo "e2e: $label ok"
}

# check_log_absent <label> <logfile> <fixed string>
check_log_absent() {
	local label="$1" logfile="$2" pattern="$3"
	if grep -qF "$pattern" "$logfile"; then
		echo "e2e: $label: log contains '$pattern' but should not" >&2
		return 1
	fi
	echo "e2e: $label ok"
}

run_case() {
	local architecture="$1" port="$2"
	local project="$work/order-service-$architecture"

	echo "e2e: [$architecture] generating project"
	"$bin" new "order-service-$architecture" \
		--group com.example \
		--architecture "$architecture" \
		--output "$work" \
		--no-interactive

	echo "e2e: [$architecture] building and testing"
	(cd "$project" && ./mvnw -q -B verify)

	local jar
	jar="$(find "$project/target" -maxdepth 1 -name '*.jar' | head -n 1)"
	if [[ -z "$jar" ]]; then
		echo "e2e: [$architecture] no jar produced in $project/target" >&2
		return 1
	fi

	echo "e2e: [$architecture] starting $(basename "$jar") on port $port"
	start_app "$jar" "$port"

	check_response "[$architecture] health" "http://127.0.0.1:$port/actuator/health" 200 '"status":"UP"'
	check_response "[$architecture] hello success" "http://127.0.0.1:$port/api/hello?name=Tee" 200 '"message":"Hello, Tee!"'
	check_response "[$architecture] validation fail" "http://127.0.0.1:$port/api/hello?name=%20" 400 '"message":"Name must not be blank."' '"data"'
	check_response "[$architecture] unknown path" "http://127.0.0.1:$port/api/nope" 404 '"message":"Resource not found: /api/nope"' '"data"'

	# A request with a known traceparent, a secret query value and a token header.
	curl -s -o /dev/null \
		-H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' \
		-H 'Authorization: Bearer topsecret' \
		"http://127.0.0.1:$port/api/hello?name=Tee&password=hunter2"

	local logfile="$work/app-$port.log"
	check_log "[$architecture] request logged" "$logfile" '\[HTTP-REQUEST\] method : GET, uri : /api/hello'
	check_log "[$architecture] response logged" "$logfile" '\[HTTP-RESPONSE\] method : GET, uri : /api/hello.*status : 200'
	check_log "[$architecture] trace correlation" "$logfile" '\[[0-9a-f]{32}-[0-9a-f]{16}\]'
	check_log "[$architecture] traceparent continued" "$logfile" '4bf92f3577b34da6a3ce929d0e0e4736'
	check_log_absent "[$architecture] actuator not logged" "$logfile" '/actuator/health'
	check_log "[$architecture] password masked" "$logfile" 'password=\*\*\*'
	check_log "[$architecture] authorization masked" "$logfile" 'authorization=\*\*\*'
	check_log_absent "[$architecture] no clear password" "$logfile" 'hunter2'
	check_log_absent "[$architecture] no clear token" "$logfile" 'topsecret'

	stop_app
}

run_case layered 18080
run_case clean 18081

echo "e2e: passed"
