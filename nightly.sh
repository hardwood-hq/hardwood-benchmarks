#!/usr/bin/env bash
#
# The nightly regression job: measures Hardwood's origin/main with the nightly profile, records
# it into hardwood-benchmark-results, lets Apache Otava find change points, confirms new
# regressions, and pushes the results, the night's report and the history page. See --help.
#
set -uo pipefail

usage() {
  cat <<EOF
The nightly regression job, for the N300 (see _plans/REGRESSION_SUITE.md).

Usage: ./nightly.sh [options]
  --commit REF      measure this Hardwood commit instead of origin/main (the same commit
                    night after night calibrates the false-alarm rate)
  --enqueue ITEM    add "VERSION-OR-COMMIT SCRIPT[,SCRIPT...]|all" to the backfill queue and
                    exit; queued items run after the night's own work while time remains,
                    and record no contender whose series already holds a settled row of the commit
  --backfill        work through the backfill queue only, without the deadline, and without
                    measuring the night's commit
  --no-alert        record, analyse and confirm, but open no issue
  --no-push         commit to the results checkout, but push nothing
  -h, --help

Everything lives under \$NIGHTLY_HOME (default ~/nightly): clones of hardwood,
hardwood-benchmarks and hardwood-benchmark-results, runs/<date>/ with each night's output and
log, the backfill queue, and the lock a run holds. A run starts only when no other run holds
the lock, no JVM runs on the machine and the clock is not pinned by someone else; it pins the
clock itself and restores it at the end. A JVM of another session seen during the run voids
it: nothing is recorded, and the night's report says why.

\$NIGHTLY_SCRIPTS (comma-separated) limits the night's own run to those scripts, to test the
job in a few minutes.

Pushing needs a GitHub token in \$NIGHTLY_HOME/token-results (Contents: read and write on
hardwood-benchmark-results only), and opening issues one in \$NIGHTLY_HOME/token-issues
(Issues: read and write on hardwood only): a fine-grained token grants its permissions on
every repository it covers, so one token for both would let the job push to hardwood.
Otava is installed into \$NIGHTLY_HOME/venv on first use.
EOF
}

HOME_DIR="${NIGHTLY_HOME:-$HOME/nightly}"
BENCH="$HOME_DIR/hardwood-benchmarks"
# Bash reads a script while it runs it, and this job checks out the benchmarks, this script
# included: it runs a copy, taken after the checkout is brought up to date.
if [ -z "${NIGHTLY_COPY:-}" ] && [[ " $* " != *" -h "* && " $* " != *" --help "* ]]; then
  mkdir -p "$HOME_DIR"
  if [ -d "$BENCH/.git" ]; then
    git -C "$BENCH" fetch -q origin \
      && git -C "$BENCH" checkout -q --detach "${NIGHTLY_BENCH_REF:-origin/main}" \
      && git -C "$BENCH" clean -q -fd -e target
  else
    git clone -q https://github.com/hardwood-hq/hardwood-benchmarks.git "$BENCH" \
      && git -C "$BENCH" checkout -q --detach "${NIGHTLY_BENCH_REF:-origin/main}"
  fi
  [ -f "$BENCH/nightly.sh" ] || { echo "no nightly.sh in $BENCH" >&2; exit 1; }
  cp "$BENCH/nightly.sh" "$HOME_DIR/nightly-running.sh"
  NIGHTLY_COPY=1 exec bash "$HOME_DIR/nightly-running.sh" "$@"
fi
REF=origin/main
ALERT=1
PUSH=1
BACKFILL=""
ENQUEUE=()
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --commit) REF="${2:?--commit needs a value}"; shift 2 ;;
    --enqueue) ENQUEUE+=("${2:?--enqueue needs a value}"); shift 2 ;;
    --backfill) BACKFILL=1; shift ;;
    --no-alert) ALERT=""; shift ;;
    --no-push) PUSH=""; shift ;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

mkdir -p "$HOME_DIR/runs"
QUEUE="$HOME_DIR/queue"
if (( ${#ENQUEUE[@]} )); then
  printf '%s\n' "${ENQUEUE[@]}" >> "$QUEUE"
  echo "queued:"; cat "$QUEUE"
  exit 0
fi

# Work after the night's own run (confirmations, backfill) starts only this many minutes after
# the job began, so that a night ends before the box is used by day.
DEADLINE_MIN="${NIGHTLY_DEADLINE_MIN:-180}"
HW="$HOME_DIR/hardwood"
RESULTS="$HOME_DIR/hardwood-benchmark-results"
DATE="$(date +%F)"
RUN="$HOME_DIR/runs/$DATE-$(date +%H%M)"
mkdir -p "$RUN"
LOG="$RUN/nightly.log"
STARTED=$(date +%s)
exec 9> "$HOME_DIR/lock"
if ! flock -n 9; then
  echo "$(date +%T) another nightly run holds $HOME_DIR/lock" | tee -a "$LOG"
  exit 0
fi

log() { echo "$(date +%T) $*" | tee -a "$LOG"; }
minutes() { echo $(( ($(date +%s) - STARTED) / 60 )); }

clone() {
  local url="$1" dir="$2"
  [ -d "$dir/.git" ] || git clone -q "$url" "$dir" >> "$LOG" 2>&1 || return 1
  git -C "$dir" fetch -q --tags --force origin >> "$LOG" 2>&1
}

RESULTS_TOKEN="$HOME_DIR/token-results"
ISSUES_TOKEN="$HOME_DIR/token-issues"
# Git over HTTPS with the token as a header, so that it is never written to a config file.
git_auth() {
  local header
  header="Authorization: Basic $(printf 'x-access-token:%s' "$(cat "$RESULTS_TOKEN")" | base64 -w0)"
  git -c http.extraHeader="$header" "$@"
}

# Commits everything changed in the results checkout and pushes it to main, rebasing on what
# arrived since; a night that loses the race three times leaves its commit for the next.
publish() {
  local message="$1" i
  git -C "$RESULTS" add -A . >> "$LOG" 2>&1
  git -C "$RESULTS" diff --cached --quiet && return 0
  git -C "$RESULTS" -c user.name="Hardwood nightly" -c user.email="nightly@hardwood.invalid" \
    commit -q -m "$message" >> "$LOG" 2>&1 || return 1
  [ -n "$PUSH" ] || { log "not pushed (--no-push)"; return 0; }
  [ -f "$RESULTS_TOKEN" ] || { log "not pushed: no $RESULTS_TOKEN"; return 1; }
  for i in 1 2 3; do
    git_auth -C "$RESULTS" pull -q --rebase origin main >> "$LOG" 2>&1 \
      && git_auth -C "$RESULTS" push -q origin HEAD:main >> "$LOG" 2>&1 && { log "pushed: $message"; return 0; }
    sleep 10
  done
  log "push failed"
  return 1
}

# A night that records nothing still says why, in its report.
skip() {
  log "skipped: $1"
  ( cd "$BENCH" && python3 charts/nightly.py report "$RESULTS" --date "$DATE" --status "Nothing recorded: $1." \
    && python3 charts/nightly.py page "$RESULTS" $( [ -d "$HW/.git" ] && echo --hardwood "$HW" ) ) >> "$LOG" 2>&1
  publish "Nightly $DATE: nothing recorded"
  exit 0
}

clone https://github.com/hardwood-hq/hardwood-benchmark-results.git "$RESULTS" || { log "cannot clone the results"; exit 1; }
git -C "$RESULTS" checkout -q main && git -C "$RESULTS" reset -q --hard origin/main
clone https://github.com/hardwood-hq/hardwood.git "$HW" || skip "cannot clone Hardwood"
log "benchmarks at $(git -C "$BENCH" rev-parse --short HEAD), results at $(git -C "$RESULTS" rev-parse --short HEAD)"

if pgrep -x java > /dev/null; then
  skip "the machine was busy: $(ps -eo args= -q "$(pgrep -d, -x java)" | cut -c1-120 | head -3 | tr '\n' ';')"
fi
cpu=/sys/devices/system/cpu/cpu0/cpufreq
if [ "$(cat $cpu/scaling_max_freq)" != "$(cat $cpu/cpuinfo_max_freq)" ]; then
  skip "the clock was pinned by someone else ($(cat $cpu/scaling_max_freq) kHz)"
fi
sudo -n profiling-setup pin >> "$LOG" 2>&1 || skip "the clock could not be pinned"
restore() {
  if pgrep -x java > /dev/null; then
    log "a JVM still runs: the clock stays pinned"
  else
    sudo -n profiling-setup restore >> "$LOG" 2>&1 && log "clock restored"
  fi
}
trap restore EXIT
log "clock pinned: $(cat $cpu/scaling_max_freq) kHz"

# Any JVM outside this job's control group (another SSH session, another service) while the
# job measures disturbs its numbers.
CGROUP="$(cat /proc/$$/cgroup)"
(
  while sleep 15; do
    for p in $(pgrep -x java); do
      # A JVM that ended since pgrep listed it (this job's forks and builds end all the time)
      # has nothing left to read: it is skipped, not taken for another session's.
      cg="$(cat "/proc/$p/cgroup" 2>/dev/null)" || continue
      cmd="$( (tr '\0' ' ' < "/proc/$p/cmdline") 2>/dev/null | cut -c1-160)"
      [ -n "$cg" ] && [ -n "$cmd" ] || continue
      [ "$cg" = "$CGROUP" ] && continue
      echo "$(date +%T) $p $cmd" >> "$RUN/disturbed"
    done
  done
) &
WATCH=$!
trap 'kill $WATCH 2>/dev/null; restore' EXIT
disturbed() { [ -s "$RUN/disturbed" ]; }

if [ ! -x "$HOME_DIR/venv/bin/otava" ]; then
  python3 -m venv "$HOME_DIR/venv" >> "$LOG" 2>&1 && "$HOME_DIR/venv/bin/pip" -q install apache-otava==0.8.0 >> "$LOG" 2>&1 \
    || skip "Otava could not be installed"
fi
export OTAVA="$HOME_DIR/venv/bin/otava"

# Installs Hardwood at commit $1 as <version>-main<sha>-SNAPSHOT (core and s3, the modules the
# benchmarks use) unless it is installed already, and prints the version; a release tag's
# version is printed as is, since Maven Central has it.
version_of() {
  local ref="$1" sha base v
  if git -C "$HW" rev-parse -q --verify "refs/tags/v$ref" > /dev/null; then echo "$ref"; return 0; fi
  sha="$(git -C "$HW" rev-parse --short=7 "$ref^{commit}" 2>/dev/null)" || return 1
  base="$(git -C "$HW" show "$sha:pom.xml" | sed -n 's|^  <version>\(.*\)</version>|\1|p' | head -1)"
  v="${base%-SNAPSHOT}-main$sha-SNAPSHOT"
  if [ -f "$HOME/.m2/repository/dev/hardwood/hardwood-s3/$v/hardwood-s3-$v.jar" ]; then echo "$v"; return 0; fi
  git -C "$HW" checkout -q --detach "$sha" && git -C "$HW" reset -q --hard && git -C "$HW" clean -q -fdx || return 1
  (
    cd "$HW" \
      && timeout 600 ./mvnw -q -B versions:set -DnewVersion="$v" -DgenerateBackupPoms=false \
      && timeout 900 ./mvnw -q -B -pl core,s3 -am install -DskipTests -Dquick
  ) > "$RUN/build-$sha.log" 2>&1
  local rc=$?
  git -C "$HW" reset -q --hard
  (( rc == 0 )) || return 1
  echo "$v"
}

# Measures version $1 with the scripts $2 (comma-separated, or all) into $RUN/$3 and records it;
# returns 2, recording nothing, when another JVM ran meanwhile.
measure() {
  local v="$1" scripts="$2" name="$3" only=() backfill=()
  [ "$scripts" = all ] || only=(--only "$scripts")
  [[ "$name" == backfill-* ]] && backfill=(--backfill)
  log "measuring $v ($scripts)"
  ( cd "$BENCH" && bash ./run-regression.sh --profile nightly "${only[@]}" --out "$RUN/$name" "$v" ) > "$RUN/$name.out" 2>&1
  log "measured $v: exit $?, $(tail -1 "$RUN/$name/progress.log" 2>/dev/null)"
  disturbed && return 2
  [ -s "$RUN/$name/manifest.json" ] || return 1
  ( cd "$BENCH" && python3 charts/record-results.py "$RUN/$name" "$v" --results "$RESULTS" --hardwood-repo "$HW" \
      --summary "$RUN/$name.summary.json" "${backfill[@]}" ) >> "$LOG" 2>&1
}

if [ -n "$BACKFILL" ]; then
  DEADLINE_MIN=100000
else
# The night's own run.
VERSION="$(version_of "$REF")" || skip "Hardwood $REF does not build (see build-*.log)"
log "Hardwood $REF is $VERSION"
measure "$VERSION" "${NIGHTLY_SCRIPTS:-all}" night
case $? in
  0) ;;
  2) skip "another JVM ran during the measurement: $(head -1 "$RUN/disturbed")" ;;
  *) skip "the run of $VERSION recorded nothing" ;;
esac
SUMMARY="$RUN/night.summary.json"

# When a contender's epoch starts tonight, its series gets comparison points under the new
# definition: the last release and the commit measured the night before.
python3 - "$SUMMARY" "$RESULTS/nights.csv" "$HW" >> "$QUEUE" <<'PY'
import csv, json, os, subprocess, sys
summary, nights, hw = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
scripts = sorted({c["script"] for c in summary["contenders"] if c["new"]})
if scripts:
    # The highest release by version: a release branch's tags are not reachable from main.
    tags = subprocess.run(["git", "-C", hw, "tag", "-l", "v[0-9]*", "--sort=-v:refname"],
                          capture_output=True, text=True).stdout.split()
    tag = tags[0] if tags else ""
    refs = [tag[1:]] if tag else []
    if os.path.exists(nights):
        earlier = [n["commit"] for n in csv.DictReader(open(nights)) if n["run"] != summary["run"]]
        refs += earlier[-1:]
    for ref in refs:
        if ref and ref != summary["commit"]:
            print(ref, ",".join(scripts))
PY

# Change points, and the confirmation of each new regression: the commit before and the commit
# after, interleaved over two rounds, six forks each.
( cd "$BENCH" && python3 charts/nightly.py analyze "$RESULTS" --summary "$SUMMARY" --plan "$RUN/plan.json" ) 2>&1 | tee -a "$LOG"
n="$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" count 2>/dev/null || echo 0)"
for (( i = 0; i < n; i++ )); do
  (( $(minutes) < DEADLINE_MIN )) || { log "deadline: confirmation $i onwards waits for the next night"; break; }
  script="$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" "$i" script)"
  before="$(version_of "$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" "$i" before)")" || { log "confirmation $i: does not build"; continue; }
  after="$(version_of "$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" "$i" after)")" || { log "confirmation $i: does not build"; continue; }
  log "confirming $script: $before against $after"
  ( cd "$BENCH" && bash ./run-regression.sh --profile nightly --only "$script" --rounds 2 --out "$RUN/confirm-$i" "$before" "$after" ) \
    > "$RUN/confirm-$i.out" 2>&1
  if disturbed; then
    log "another JVM ran during confirmation $i ($(head -1 "$RUN/disturbed")): it and the rest stay pending"
    break
  fi
  ( cd "$BENCH" && python3 charts/nightly.py confirm "$RESULTS" --plan "$RUN/plan.json" --item "$i" --out "$RUN/confirm-$i" \
      --before-version "$before" --after-version "$after" ) 2>&1 | tee -a "$LOG"
done

( cd "$BENCH" && python3 charts/nightly.py report "$RESULTS" --date "$DATE" --summary "$SUMMARY" --out "$RUN/night" \
    && python3 charts/nightly.py page "$RESULTS" --hardwood "$HW" ) >> "$LOG" 2>&1
publish "Nightly $DATE: $VERSION"
if [ -n "$ALERT" ] && [ -n "$PUSH" ] && [ -f "$ISSUES_TOKEN" ]; then
  ( cd "$BENCH" && python3 charts/nightly.py issues "$RESULTS" --repo hardwood-hq/hardwood --token-file "$ISSUES_TOKEN" ) 2>&1 | tee -a "$LOG"
  publish "Nightly $DATE: issues of confirmed regressions"
fi

fi

# Backfill while time remains: each queued item records a version with some or all scripts.
while [ -s "$QUEUE" ] && (( $(minutes) < DEADLINE_MIN )); do
  read -r ref scripts < "$QUEUE"
  sed -i 1d "$QUEUE"
  [ -n "${ref:-}" ] || continue
  v="$(version_of "$ref")" || { log "backfill $ref: does not build"; continue; }
  measure "$v" "${scripts:-all}" "backfill-$(echo "$v" | tr -c 'A-Za-z0-9.-' _)"
  case $? in
    0) ;;
    2) log "another JVM ran during the backfill of $v ($(head -1 "$RUN/disturbed")): requeued"
       { echo "$ref ${scripts:-all}"; cat "$QUEUE"; } > "$QUEUE.new" && mv "$QUEUE.new" "$QUEUE"
       break ;;
    *) log "backfill $v recorded nothing"; continue ;;
  esac
  ( cd "$BENCH" && python3 charts/nightly.py page "$RESULTS" --hardwood "$HW" ) >> "$LOG" 2>&1
  publish "Backfill $v (${scripts:-all})"
done
log "done in $(minutes) min"
