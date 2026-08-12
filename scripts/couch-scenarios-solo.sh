#!/usr/bin/env bash
# The shared cross-app scenario script, run against a real CouchDB with this checkout playing both
# devices — see bopa's docs/couch-sync-vectors/interop-scenarios.json.
#
# bopa's scripts/couch-scenarios.sh is the real proof: it interleaves *bopa's* Swift engine with
# notable's Kotlin one, and only that can show the two implementations agree. But every one of the
# 22 scenarios has an iPad step, so without a bopa checkout notable's half is not partly runnable,
# it is unrunnable — 22 scenarios that have never been executed here at all.
#
# This runs the same file, same steps, same server, with a second notable invocation standing in for
# the iPad (COUCH_SCENARIO_DEVICE=ipad; notable's harness implements a superset of the ops the iPad
# steps use). What that proves:
#
#   - notable's engine satisfies the shared script end to end, on all 22 scenarios
#   - two independent engines, two independent stores, one real database, converge
#
# What it does not prove: that Swift agrees. Nothing here executes bopa's code. When both checkouts
# are present, run bopa's driver — this is the weaker check available to one repository alone.
#
#   ./scripts/couch-scenarios-solo.sh [couchdb-url]
#   BOPA_DIR=~/src/bopa ./scripts/couch-scenarios-solo.sh
set -uo pipefail

COUCH_URL="${1:-${COUCH_URL:-http://127.0.0.1:5984}}"
COUCH_USER="${COUCH_USER:-sync}"
COUCH_PASSWORD="${COUCH_PASSWORD:-testsyncpw}"
# Creating and dropping the per-run database is the one thing here that is not something the app
# does, and a sync account with rights over `notes` alone answers 401 to it. Separate credentials,
# defaulting to the sync account so a server that does grant it needs no extra configuration.
COUCH_ADMIN_USER="${COUCH_ADMIN_USER:-$COUCH_USER}"
COUCH_ADMIN_PASSWORD="${COUCH_ADMIN_PASSWORD:-$COUCH_PASSWORD}"
NOTABLE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# The scenario file lives in bopa so there is one copy and it cannot drift. A sibling checkout by
# default; checked below rather than guessed at, so a wrong path says so instead of failing in Gradle.
BOPA_DIR="${BOPA_DIR:-$NOTABLE_DIR/../bopa}"
SCENARIO_FILE="${SCENARIO_FILE:-$BOPA_DIR/docs/couch-sync-vectors/interop-scenarios.json}"

RUN_ID="${COUCH_SCENARIO_RUN:-$(date +%s)-$$}"
STATE_DIR="${COUCH_SCENARIO_STATE_DIR:-$(mktemp -d)}"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*"; }

# Only export a default that is really there: pointing JAVA_HOME at a missing directory is worse
# than leaving it unset, because Gradle then stops looking.
default_dir() {
  local name=$1 path=$2
  [ -n "${!name:-}" ] && return 0
  [ -d "$path" ] && export "$name=$path"
  return 0
}
default_dir JAVA_HOME /opt/homebrew/opt/openjdk@21
default_dir ANDROID_HOME /opt/homebrew/share/android-commandlinetools
[ -n "${ANDROID_HOME:-}" ] && export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

# A database per run. Every device replays the change feed from zero, so sharing a database with
# past runs means every run re-reads every run before it — and each device's store ends up holding
# the whole history rather than the scenario under test. It also keeps concurrent runs apart.
COUCH_DATABASE="${COUCH_DATABASE:-solo-$(printf '%s' "$RUN_ID" | tr -c 'a-z0-9' '-')}"

say "preflight: $COUCH_URL"
[ -f "$SCENARIO_FILE" ] || {
  fail "no scenario file at $SCENARIO_FILE — set BOPA_DIR or SCENARIO_FILE"; exit 1; }
curl -fsS --user "$COUCH_USER:$COUCH_PASSWORD" "$COUCH_URL/_up" >/dev/null || {
  fail "cannot reach the server"; exit 1; }
curl -fsS --user "$COUCH_ADMIN_USER:$COUCH_ADMIN_PASSWORD" \
  -X PUT "$COUCH_URL/$COUCH_DATABASE" >/dev/null || {
  fail "cannot create $COUCH_DATABASE — set COUCH_ADMIN_USER/COUCH_ADMIN_PASSWORD"; exit 1; }
# The sync account has to be able to read and write it, or every step fails as 401 instead.
curl -fsS --user "$COUCH_ADMIN_USER:$COUCH_ADMIN_PASSWORD" -X PUT \
  -H 'Content-Type: application/json' \
  -d "{\"members\":{\"names\":[\"$COUCH_USER\"]},\"admins\":{\"names\":[]}}" \
  "$COUCH_URL/$COUCH_DATABASE/_security" >/dev/null || {
  fail "cannot grant $COUCH_USER access to $COUCH_DATABASE"; exit 1; }
STEPS=$(python3 -c '
import json,sys
print(max(len(s["steps"]) for s in json.load(open(sys.argv[1]))["scenarios"]))' "$SCENARIO_FILE")
SCENARIOS=$(python3 -c '
import json,sys
print(len(json.load(open(sys.argv[1]))["scenarios"]))' "$SCENARIO_FILE")
echo "database $COUCH_DATABASE · $SCENARIOS scenarios · $STEPS step indices · state in $STATE_DIR"

failures=()

# Documents written by neither app — a future schema, a corrupt body — injected straight into the
# database, because the point of those scenarios is input no client would produce.
inject_server_step() {
  python3 - "$SCENARIO_FILE" "$1" "$RUN_ID" "$COUCH_URL" "$COUCH_DATABASE" \
    "$COUCH_USER" "$COUCH_PASSWORD" <<'PY'
import base64, json, sys, urllib.request, urllib.error

path, step, run, url, db, user, password = sys.argv[1:8]
step = int(step)
auth = base64.b64encode(f"{user}:{password}".encode()).decode()

def doc_id(scenario, logical):
    raw = f"{scenario}-{logical}-{run}"
    prefix = "notebook" if logical.startswith("nb") else "folder" if logical.startswith("fd") else "page"
    return f"{prefix}:{raw}"

def request(method, target, body=None):
    req = urllib.request.Request(target, method=method, data=body)
    req.add_header("Authorization", f"Basic {auth}")
    if body:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, {}

for scenario in json.load(open(path))["scenarios"]:
    steps = scenario["steps"]
    if step >= len(steps) or steps[step].get("device") != "server":
        continue
    for op in steps[step]["do"]:
        if op["op"] != "putRaw":
            raise SystemExit(f"unsupported server op {op['op']}")
        target = f"{url}/{db}/{doc_id(scenario['name'], op['doc'])}"
        # Whatever revision is already there, so the injection lands rather than 409s.
        status, existing = request("GET", target)
        body = dict(op["json"])
        if status == 200 and "_rev" in existing:
            body["_rev"] = existing["_rev"]
        status, _ = request("PUT", target, json.dumps(body).encode())
        if status not in (200, 201):
            raise SystemExit(f"injecting {target} failed with {status}")
        print(f"  injected {doc_id(scenario['name'], op['doc'])}")
PY
}

run_device() {
  local device=$1 step=$2 output status
  # Passed as project properties, not environment: app/build.gradle forwards them to the test JVM,
  # and a `-P` value belongs to this build. The environment a warm Gradle daemon reports belongs to
  # whichever build started it, so a per-step variable read that way is silently the wrong step —
  # and a scenario test that finds no step assumes itself away, which looks exactly like a pass.
  # `--rerun` re-runs just this test task: Gradle does not track a test JVM's environment as a task
  # input, so without it every step after the first is UP-TO-DATE and leaves no receipt.
  output=$(cd "$NOTABLE_DIR" && ./gradlew --quiet :app:testDebugUnitTest --rerun \
    -PNOTABLE_COUCH_URL="$COUCH_URL" -PNOTABLE_COUCH_USER="$COUCH_USER" \
    -PNOTABLE_COUCH_PASSWORD="$COUCH_PASSWORD" -PNOTABLE_COUCH_DATABASE="$COUCH_DATABASE" \
    -PCOUCH_SCENARIO_FILE="$SCENARIO_FILE" -PCOUCH_SCENARIO_STEP="$step" \
    -PCOUCH_SCENARIO_STATE_DIR="$STATE_DIR" -PCOUCH_SCENARIO_RUN="$RUN_ID" \
    -PCOUCH_SCENARIO_DEVICE="$device" \
    --tests 'com.ethran.notable.sync.couch.CouchScenarioTest' 2>&1)
  status=$?
  printf '%s\n' "$output" | grep -vE "^Running locally|^$" || true
  # Gradle's console output names the failing test but not what it asserted, and the assertion text
  # is the whole diagnosis — which scenario, which document, what it held instead. Read it out of
  # the JUnit XML, which the next step's --rerun overwrites.
  (( status != 0 )) && python3 - "$NOTABLE_DIR/app/build/test-results/testDebugUnitTest" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
for path in glob.glob(os.path.join(sys.argv[1], "*CouchScenarioTest.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        for failure in case.findall("failure"):
            for line in (failure.get("message") or "").splitlines():
                print(f"    {line}")
PY
  (( status == 0 )) && echo "  $device ok"
  return $status
}

# How many scenarios a device is supposed to act on at this step. A runner that skipped itself exits
# 0 and prints nothing, so "it passed" and "it never ran" look identical without this.
check_receipt() {
  local step=$1 device=$2 expected actual
  expected=$(python3 -c '
import json,sys
step, device = int(sys.argv[2]), sys.argv[3]
print(sum(1 for s in json.load(open(sys.argv[1]))["scenarios"]
          if step < len(s["steps"]) and s["steps"][step].get("device") == device))' \
    "$SCENARIO_FILE" "$step" "$device")
  [ "$expected" = "0" ] && return 0
  actual=$(cat "$STATE_DIR/ran.$device.$step" 2>/dev/null || echo "none")
  if [ "$actual" != "$expected" ]; then
    fail "  $device ran $actual of $expected scenarios at step $step — it skipped itself"
    return 1
  fi
  return 0
}

for (( step = 0; step < STEPS; step++ )); do
  say "step $step"
  inject_server_step "$step" || failures+=("server injection at step $step")
  # Same order as bopa's driver, so a scenario that depends on who wrote first behaves the same way.
  for device in ipad boox; do
    run_device "$device" "$step" || failures+=("$device step $step")
    check_receipt "$step" "$device" || failures+=("$device did not run step $step")
  done
done

say "result"
if (( ${#failures[@]} )); then
  fail "FAILED:"
  printf '  %s\n' "${failures[@]}"
  echo "kept for inspection — device state: $STATE_DIR, database: $COUCH_URL/$COUCH_DATABASE"
  exit 1
fi
printf '\033[1;32mAll %s scenarios converged\033[0m (one implementation, both roles).\n' "$SCENARIOS"
curl -fsS --user "$COUCH_ADMIN_USER:$COUCH_ADMIN_PASSWORD" \
  -X DELETE "$COUCH_URL/$COUCH_DATABASE" >/dev/null
rm -rf "$STATE_DIR"
