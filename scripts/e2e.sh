#!/usr/bin/env bash
# End-to-end check: generate a project per architecture, build it with Maven,
# start it, and exercise signing, JSend, logging, masking, and the pooled REST
# client. Requires JDK 25, openssl, and network access on the first run.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="$root/bin/spring-gen"
work="$(mktemp -d)"
pid=""
signing_secret="e2e-shared-secret"

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
	REQUEST_SIGNING_SECRET="$signing_secret" java -jar "$jar" --server.port="$port" \
		>"$work/app-$port.log" 2>&1 &
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

# sign <requestId> <timestamp> <body>
sign() {
	local request_id="$1" timestamp="$2" body="${3:-}"
	printf '%s' "${request_id}${timestamp}$(printf '%s' "$body" | base64 -w0)" \
		| openssl dgst -sha256 -hmac "$signing_secret" -binary | base64 -w0
}

# signed_response <url> [signed-body] [timestamp-offset-ms] [sent-body] [method]
signed_response() {
	local url="$1" body="${2:-}" offset="${3:-0}" method="${5:-GET}"
	local sent="${4:-$body}"
	local request_id timestamp signature
	request_id="$(cat /proc/sys/kernel/random/uuid)"
	timestamp="$(( $(date +%s%3N) + offset ))"
	signature="$(sign "$request_id" "$timestamp" "$body")"

	if [[ -n "$sent" ]]; then
		curl -s -w '\n%{http_code}' -X "$method" \
			-H "X-Request-Id: $request_id" -H "X-Request-Timestamp: $timestamp" \
			-H "X-Request-Signature: $signature" -H 'Content-Type: application/json' \
			-d "$sent" "$url"
	else
		curl -s -w '\n%{http_code}' -X "$method" \
			-H "X-Request-Id: $request_id" -H "X-Request-Timestamp: $timestamp" \
			-H "X-Request-Signature: $signature" "$url"
	fi
}

# check_response <label> <url> <expected status> <expected body fragment> [forbidden fragment]
check_response() {
	local label="$1" url="$2" want_status="$3" want_body="$4" forbidden="${5:-}"
	local response status body

	response="$(curl -s -w '\n%{http_code}' "$url")"
	status="${response##*$'\n'}"
	body="${response%$'\n'*}"

	assert_response "$label" "$status" "$body" "$want_status" "$want_body" "$forbidden"
}

# check_signed <label> <url> <expected status> <expected body fragment>
check_signed() {
	local label="$1" url="$2" want_status="$3" want_body="$4"
	local response status body

	response="$(signed_response "$url")"
	status="${response##*$'\n'}"
	body="${response%$'\n'*}"

	assert_response "$label" "$status" "$body" "$want_status" "$want_body" ""
}

assert_response() {
	local label="$1" status="$2" body="$3" want_status="$4" want_body="$5" forbidden="${6:-}"

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

	# Health stays unsigned.
	check_response "[$architecture] health" "http://127.0.0.1:$port/actuator/health" 200 '"status":"UP"'

	# Signing enforcement.
	check_response "[$architecture] unsigned rejected" \
		"http://127.0.0.1:$port/api/hello?name=Tee" 401 '"message":"Missing header X-Request-Id."'
	check_signed "[$architecture] signed hello" \
		"http://127.0.0.1:$port/api/hello?name=Tee" 200 '"message":"Hello, Tee!"'
	check_signed "[$architecture] validation fail" \
		"http://127.0.0.1:$port/api/hello?name=%20" 400 '"message":"Name must not be blank."'
	check_signed "[$architecture] unknown path" \
		"http://127.0.0.1:$port/api/nope" 404 '"message":"Resource not found: /api/nope"'

	local stale
	stale="$(signed_response "http://127.0.0.1:$port/api/hello?name=Tee" "" -600000)"
	assert_response "[$architecture] stale timestamp rejected" "${stale##*$'\n'}" "${stale%$'\n'*}" \
		401 '"message":"Request timestamp is outside the allowed window."'

	local tampered
	tampered="$(signed_response "http://127.0.0.1:$port/api/hello" '{"name":"Tee"}' 0 '{"name":"Eve"}' POST)"
	assert_response "[$architecture] tampered body rejected" "${tampered##*$'\n'}" "${tampered%$'\n'*}" \
		401 '"message":"Invalid request signature."'

	# A signed remote call: exercises the pooled client, signing, logging, masking.
	local request_id timestamp signature
	request_id="$(cat /proc/sys/kernel/random/uuid)"
	timestamp="$(date +%s%3N)"
	signature="$(sign "$request_id" "$timestamp" "")"
	check_response_remote() {
		local label="$1"
		local response status body
		response="$(curl -s -w '\n%{http_code}' \
			-H "X-Request-Id: $request_id" -H "X-Request-Timestamp: $timestamp" \
			-H "X-Request-Signature: $signature" \
			-H 'Authorization: Bearer topsecret' \
			-H 'traceparent: 00-11111111111111111111111111111111-2222222222222222-01' \
			"http://127.0.0.1:$port/api/hello/remote?name=Tee&password=hunter2")"
		status="${response##*$'\n'}"
		body="${response%$'\n'*}"
		assert_response "$label" "$status" "$body" 200 '"message":"Hello, Tee!"' ""
	}
	check_response_remote "[$architecture] signed remote success"

	local logfile="$work/app-$port.log"
	check_log "[$architecture] request logged" "$logfile" '\[HTTP-REQUEST\] method : GET, uri : /api/hello'
	check_log "[$architecture] response logged" "$logfile" '\[HTTP-RESPONSE\] method : GET, uri : /api/hello.*status : 200'
	check_log "[$architecture] trace correlation" "$logfile" '\[[0-9a-f]{32}-[0-9a-f]{16}\]'
	check_log "[$architecture] traceparent continued" "$logfile" '11111111111111111111111111111111-[0-9a-f]{16}'
	check_log_absent "[$architecture] actuator not logged" "$logfile" '/actuator/health'
	check_log "[$architecture] password masked" "$logfile" 'password=\*\*\*'
	check_log "[$architecture] authorization masked" "$logfile" 'authorization=\*\*\*'
	check_log_absent "[$architecture] no clear password" "$logfile" 'hunter2'
	check_log_absent "[$architecture] no clear token" "$logfile" 'topsecret'
	check_log "[$architecture] security rejection logged" "$logfile" '\[SECURITY-ERROR\] (Missing header|Invalid request signature|Request timestamp)'

	check_log "[$architecture] rest client request" "$logfile" '\[RESTCLIENT-REQUEST\] method : GET, uri : http://localhost:'"$port"'/api/hello\?name=Tee'
	check_log "[$architecture] rest client response" "$logfile" '\[RESTCLIENT-RESPONSE\] method : GET, uri : http://localhost:'"$port"'/api/hello\?name=Tee, status : 200'
	check_log "[$architecture] rest client trace propagated" "$logfile" '11111111111111111111111111111111-[0-9a-f]{16}.*\[RESTCLIENT-REQUEST\]'
	check_log "[$architecture] downstream request same trace" "$logfile" '11111111111111111111111111111111-[0-9a-f]{16}.*\[HTTP-REQUEST\] method : GET, uri : /api/hello\?name=Tee'

	stop_app
}

# The Redis option adds a dependency and tests but is not started here.
run_redis_case() {
	local project="$work/redis-demo"
	echo "e2e: [redis] generating project with request id checking"
	"$bin" new redis-demo --group com.example --architecture layered \
		--request-id-check --output "$work" --no-interactive

	echo "e2e: [redis] building and testing"
	(cd "$project" && ./mvnw -q -B verify)
	echo "e2e: [redis] generated and built"
}

run_case layered 18080
run_case clean 18081
run_redis_case

echo "e2e: passed"
