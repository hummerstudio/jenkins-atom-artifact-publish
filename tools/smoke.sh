#!/usr/bin/env bash
# URL shape per provider, auth header, checksum, path templates, and the failure paths.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((20000 + RANDOM % 900))
python3 tools/fake_repo.py "$port" "$log" & server=$!
trap 'kill $server 2>/dev/null; wait $server 2>/dev/null' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done

# a workspace with two runnable outputs
ws="$(mktemp -d)"; mkdir -p "$ws/build/libs" "$ws/reports"
printf 'jar-bytes' >"$ws/build/libs/app-1.0.0.jar"
printf '<html/>' >"$ws/reports/index.html"
cat >"$ws/input.json" <<EOF
{"provider": "artifactory", "baseUrl": "http://127.0.0.1:$port", "repository": "libs-release",
 "username": "deployer", "password": "s3cret", "files": "build/libs/*.jar",
 "pathTemplate": "acme/app/1.0.0/{name}", "properties": "build=42;os=mac"}
EOF
run "$ws" >"$ws/atom.log" 2>&1 || fail "upload failed: $(cat "$ws/output.json")"
[ "$(output "$ws" uploaded)" = "1" ] || fail "uploaded count: $(cat "$ws/output.json")"
[ "$(output "$ws" bytes)" = "9" ] || fail "bytes: $(cat "$ws/output.json")"
grep -q '"path": "/artifactory/libs-release/acme/app/1.0.0/app-1.0.0.jar?properties=build%3D42%3Bos%3Dmac"' "$log" || fail "artifactory URL: $(tail -1 "$log")"
grep -q '"authorization": "Basic ZGVwbG95ZXI6czNjcmV0"' "$log" || fail "basic auth: $(tail -1 "$log")"
grep -q '"checksum": "\w\{64\}"' "$log" || fail "sha256 header missing: $(tail -1 "$log")"

# nexus uses /repository/ and the file name alone
: >"$log"
cat >"$ws/input.json" <<EOF
{"provider": "nexus", "baseUrl": "http://127.0.0.1:$port", "repository": "releases",
 "token": "nxt", "files": "reports/index.html", "pathTemplate": "site/{name}"}
EOF
run "$ws" >"$ws/atom.log" 2>&1 || fail "nexus upload failed: $(cat "$ws/output.json")"
grep -q '"path": "/repository/releases/site/index.html"' "$log" || fail "nexus URL: $(tail -1 "$log")"
grep -q '"authorization": "Bearer nxt"' "$log" || fail "bearer auth: $(tail -1 "$log")"
grep -q '"contentType": "text/html; charset=utf-8"' "$log" || fail "content type: $(tail -1 "$log")"

# several files in one call, and {dir} keeps the layout
: >"$log"
cat >"$ws/input.json" <<EOF
{"provider": "artifactory", "baseUrl": "http://127.0.0.1:$port", "repository": "r",
 "token": "t", "files": "**/*.jar, **/*.html", "pathTemplate": "{path}"}
EOF
run "$ws" >"$ws/atom.log" 2>&1 || fail "multi upload failed: $(cat "$ws/output.json")"
[ "$(output "$ws" uploaded)" = "2" ] || fail "two files expected: $(cat "$ws/output.json")"
grep -q '/artifactory/r/build/libs/app-1.0.0.jar' "$log" || fail "path template {path}: $(head -2 "$log")"

# 401 is a credentials problem (USER), not a repository problem
kill $server 2>/dev/null; wait $server 2>/dev/null
port2=$((port + 1))
python3 tools/fake_repo.py "$port2" "$log" --status 401 & server=$!
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port2/" && break; sleep 0.1; done
cat >"$ws/input.json" <<EOF
{"provider": "artifactory", "baseUrl": "http://127.0.0.1:$port2", "repository": "r",
 "token": "bad", "files": "**/*.jar"}
EOF
run "$ws" >"$ws/atom.log" 2>&1 && fail "401 must fail the step"
grep -q '"errorType":1' "$ws/output.json" || fail "401 should be USER(1): $(cat "$ws/output.json")"
grep -q "HTTP 401" "$ws/output.json" || fail "status not reported: $(cat "$ws/output.json")"

# failOnError=false records the failure and moves on
cat >"$ws/input.json" <<EOF
{"provider": "artifactory", "baseUrl": "http://127.0.0.1:$port2", "repository": "r",
 "token": "bad", "files": "**/*.jar", "failOnError": "false"}
EOF
run "$ws" >"$ws/atom.log" 2>&1 || fail "failOnError=false must not fail"
[ "$(output "$ws" failed)" = "1" ] || fail "failed count: $(cat "$ws/output.json")"

# bad input
cat >"$ws/input.json" <<EOF
{"provider": "artifactory", "baseUrl": "http://127.0.0.1:$port", "repository": "r", "files": "nope/*.jar"}
EOF
run "$ws" >"$ws/atom.log" 2>&1 && fail "no credentials must be refused"
grep -q "credentials are required" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

echo "smoke ok: artifactory/nexus URLs, basic+bearer auth, checksum, path templates, 401, opt-out, bad input"
