#!/usr/bin/env bash
#
# The nightly regression job: measures Hardwood's origin/main with the nightly profile, records
# it into hardwood-benchmark-results, lets Apache Otava find change points, measures each new
# regression's two commits side by side, and pushes the results, the night's report and the
# history page. See --help.
#
set -uo pipefail

usage() {
  cat <<EOF
The nightly regression job (see _designs/REGRESSION_SUITE.md, and README.md for setting up a machine).

Usage: ./nightly.sh [options]
  --commit REF      measure this Hardwood commit instead of origin/main (the same commit
                    night after night calibrates the false-alarm rate)
  --enqueue ITEM    add "VERSION-OR-COMMIT SCRIPT[,SCRIPT...]|all" to the backfill queue and
                    exit; queued items run after the night's own work while time remains,
                    and record no contender whose series already holds a settled row of the commit
  --backfill        work through the backfill queue only, without the deadline, and without
                    measuring the night's commit; the change points are analysed once the
                    queue is done
  --no-alert        record, analyse and run side by side, but open no issue: neither for a
                    regression nor for a failing night
  --no-push         commit to the results checkout, but push nothing
  -h, --help

The machine's name, from \$BENCH_MACHINE_NAME or /etc/hardwood-benchmarks/machine (lower-case
letters, digits and dashes), names its directory in hardwood-benchmark-results, which holds
everything it records, and its issue while its nights fail.

Everything lives under \$NIGHTLY_HOME (default ~/nightly): clones of hardwood,
hardwood-benchmarks and hardwood-benchmark-results, runs/<date>/ with each night's output and
log, the backfill queue, and the lock a run holds. A run starts only when no other run holds
the lock, no JVM runs on the machine and the clock is not pinned by someone else. With
profiling-setup installed (the bench_host role of cloud-boxes), it pins the clock and restores
it at the end; without, it runs on the clock as it is and logs that. A JVM of another session seen during the run voids
it: nothing is recorded, and the night's report says why. A night that records nothing, or
cannot analyse what it recorded, exits 1 and, with alerts on, keeps one issue on hardwood open
until a night succeeds.

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
PUSH_FAILED=""
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

# Work after the night's own run (side-by-side runs, backfill) starts only this many minutes after
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

MACHINE="${BENCH_MACHINE_NAME:-$(cat /etc/hardwood-benchmarks/machine 2>/dev/null)}"
if [[ ! "$MACHINE" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  log "no machine name: set /etc/hardwood-benchmarks/machine or \$BENCH_MACHINE_NAME (got '$MACHINE')"
  exit 1
fi
export BENCH_MACHINE_NAME="$MACHINE"
# The machine's directory in the results: all it records, and all that the steps below read.
DATA="$RESULTS/$MACHINE"
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

# The job's own identity for the commits it makes, a rebase's included.
results_git() { git -C "$RESULTS" -c user.name="Hardwood nightly" -c user.email="nightly@hardwood.invalid" "$@"; }

# The page at the root of the results, the one place machines share: written from the tree as it is.
index() {
  ( cd "$BENCH" && python3 charts/nightly.py index "$RESULTS" ) >> "$LOG" 2>&1 \
    && git -C "$RESULTS" add index.html machines.json >> "$LOG" 2>&1
}

# Commits everything changed in the results checkout and pushes it to main, rebasing on what
# arrived since. A machine writes only its own directory, so a rebase can conflict only in the
# page at the root, which is written again from the merged tree. A commit that three tries do
# not push is lost, as the next run starts from origin/main: the night alerts.
publish() {
  local message="$1" i
  index || return 1
  git -C "$RESULTS" add -A . >> "$LOG" 2>&1
  git -C "$RESULTS" diff --cached --quiet && return 0
  results_git commit -q -m "$message" >> "$LOG" 2>&1 || return 1
  [ -n "$PUSH" ] || { log "not pushed (--no-push)"; return 0; }
  [ -f "$RESULTS_TOKEN" ] || { log "not pushed: no $RESULTS_TOKEN"; return 1; }
  for i in 1 2 3; do
    if git_auth -C "$RESULTS" fetch -q origin main >> "$LOG" 2>&1 \
        && results_git rebase -q -X theirs FETCH_HEAD >> "$LOG" 2>&1; then
      index && { git -C "$RESULTS" diff --cached --quiet || results_git commit -q --amend --no-edit >> "$LOG" 2>&1; } \
        && git_auth -C "$RESULTS" push -q origin HEAD:main >> "$LOG" 2>&1 && { log "pushed: $message"; PUSH_FAILED=""; return 0; }
    else
      git -C "$RESULTS" rebase --abort >> "$LOG" 2>&1
    fi
    sleep 10
  done
  log "push failed"
  PUSH_FAILED=1
  return 1
}

# Keeps the issue on Hardwood that stays open while nights fail: health --failing REASON or --ok.
health() {
  [ -n "$ALERT" ] && [ -n "$PUSH" ] && [ -f "$ISSUES_TOKEN" ] || return 0
  ( cd "$BENCH" && python3 charts/nightly.py health --machine "$MACHINE" --date "$DATE" --repo hardwood-hq/hardwood \
      --token-file "$ISSUES_TOKEN" "$@" ) 2>&1 | tee -a "$LOG"
}

# A night that records nothing still says why, in its report, and alerts. A backfill run, by
# hand or by day, leaves the night's report and the alert alone.
skip() {
  log "skipped: $1"
  [ -z "$BACKFILL" ] || exit 1
  ( cd "$BENCH" && python3 charts/nightly.py report "$DATA" --date "$DATE" --status "Nothing recorded: $1." \
    && python3 charts/nightly.py page "$DATA" $( [ -d "$HW/.git" ] && echo --hardwood "$HW" ) ) >> "$LOG" 2>&1
  publish "Nightly $DATE: nothing recorded"
  health --failing "nothing recorded: $1."
  exit 1
}

# Without the results checkout there is no report to write; the alert still goes out.
results_broken() {
  log "$1"
  [ -n "$BACKFILL" ] || health --failing "nothing recorded: $1."
  exit 1
}
clone https://github.com/hardwood-hq/hardwood-benchmark-results.git "$RESULTS" || results_broken "cannot clone or fetch the results"
git -C "$RESULTS" checkout -q main && git -C "$RESULTS" reset -q --hard origin/main \
  || results_broken "cannot bring the results checkout to origin/main"
mkdir -p "$DATA"
clone https://github.com/hardwood-hq/hardwood.git "$HW" || skip "cannot clone Hardwood"
log "benchmarks at $(git -C "$BENCH" rev-parse --short HEAD), results at $(git -C "$RESULTS" rev-parse --short HEAD)"

if pgrep -x java > /dev/null; then
  skip "the machine was busy: $(ps -eo args= -q "$(pgrep -d, -x java)" | cut -c1-120 | head -3 | tr '\n' ';')"
fi
cpu=/sys/devices/system/cpu/cpu0/cpufreq
if [ "$(cat $cpu/scaling_max_freq)" != "$(cat $cpu/cpuinfo_max_freq)" ]; then
  skip "the clock was pinned by someone else ($(cat $cpu/scaling_max_freq) kHz)"
fi
PINNED=""
if command -v profiling-setup > /dev/null; then
  sudo -n profiling-setup pin >> "$LOG" 2>&1 || skip "the clock could not be pinned"
  PINNED=1
  log "clock pinned: $(cat $cpu/scaling_max_freq) kHz"
else
  log "clock not pinned (no profiling-setup): governor $(cat $cpu/scaling_governor), up to $(cat $cpu/scaling_max_freq) kHz"
fi
restore() {
  if [ -z "$PINNED" ]; then
    return 0
  elif pgrep -x java > /dev/null; then
    log "a JVM still runs: the clock stays pinned"
  else
    sudo -n profiling-setup restore >> "$LOG" 2>&1 && log "clock restored"
  fi
}
trap restore EXIT

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
  ( cd "$BENCH" && python3 charts/record-results.py "$RUN/$name" "$v" --results "$DATA" --hardwood-repo "$HW" \
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
# definition: every release the history page marks, so that each series holds every release,
# and the commit measured the night before.
RELEASES="$(cd "$BENCH" && python3 charts/nightly.py releases "$HW" 2>> "$LOG")" \
  || log "the releases could not be listed: a new series gets only the previous night's commit"
python3 - "$SUMMARY" "$DATA/nights.csv" "$RELEASES" >> "$QUEUE" <<'PY'
import csv, json, os, sys
summary, nights, releases = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3].split()
scripts = sorted({c["script"] for c in summary["contenders"] if c["new"]})
if scripts:
    refs = list(releases)
    if os.path.exists(nights):
        earlier = [n["commit"] for n in csv.DictReader(open(nights)) if n["run"] != summary["run"]]
        refs += earlier[-1:]
    for ref in refs:
        if ref and ref != summary["commit"]:
            print(ref, ",".join(scripts))
PY

# Change points, as Otava finds them. A night whose analysis fails publishes what it recorded,
# says so in its report, and alerts.
( cd "$BENCH" && python3 charts/nightly.py analyze "$DATA" --date "$DATE" --summary "$SUMMARY" --plan "$RUN/plan.json" ) 2>&1 | tee -a "$LOG"
if (( PIPESTATUS[0] != 0 )); then
  log "analysis failed"
  ( cd "$BENCH" && python3 charts/nightly.py report "$DATA" --date "$DATE" --summary "$SUMMARY" --out "$RUN/night" \
      --status "Analysis failed: recorded, but no change points were looked for (see the night's log on the machine)." \
    && python3 charts/nightly.py page "$DATA" --hardwood "$HW" ) >> "$LOG" 2>&1
  publish "Nightly $DATE: $VERSION, not analysed"
  health --failing "the analysis of $VERSION failed."
  exit 1
fi

# Each new regression's commit before and commit after, measured again side by side,
# interleaved over two rounds, six forks each: evidence for its issue, not a verdict.
n="$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" count)"
for (( i = 0; i < n; i++ )); do
  (( $(minutes) < DEADLINE_MIN )) || { log "deadline: no side-by-side run from $i onwards"; break; }
  script="$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" "$i" script)"
  before="$(version_of "$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" "$i" before)")" || { log "side by side $i: does not build"; continue; }
  after="$(version_of "$(cd "$BENCH" && python3 charts/nightly.py plan "$RUN/plan.json" "$i" after)")" || { log "side by side $i: does not build"; continue; }
  log "side by side $script: $before against $after"
  ( cd "$BENCH" && bash ./run-regression.sh --profile nightly --only "$script" --rounds 2 --out "$RUN/side-by-side-$i" "$before" "$after" ) \
    > "$RUN/side-by-side-$i.out" 2>&1
  if disturbed; then
    log "another JVM ran during side-by-side run $i ($(head -1 "$RUN/disturbed")): no side-by-side run from $i onwards"
    break
  fi
  ( cd "$BENCH" && python3 charts/nightly.py side-by-side "$DATA" --plan "$RUN/plan.json" --item "$i" --out "$RUN/side-by-side-$i" \
      --before-version "$before" --after-version "$after" ) 2>&1 | tee -a "$LOG"
done

# With alerts off, what was found is marked so, and turning alerts on later opens no backlog.
[ -n "$ALERT" ] || ( cd "$BENCH" && python3 charts/nightly.py mute "$DATA" ) >> "$LOG" 2>&1
( cd "$BENCH" && python3 charts/nightly.py report "$DATA" --date "$DATE" --summary "$SUMMARY" --out "$RUN/night" \
    && python3 charts/nightly.py page "$DATA" --hardwood "$HW" ) >> "$LOG" 2>&1
publish "Nightly $DATE: $VERSION"
issued=0
if [ -n "$ALERT" ] && [ -n "$PUSH" ] && [ -f "$ISSUES_TOKEN" ]; then
  # An issue that could not be opened stays unalerted, for the next night to open.
  ( cd "$BENCH" && python3 charts/nightly.py issues "$DATA" --repo hardwood-hq/hardwood --token-file "$ISSUES_TOKEN" ) 2>&1 | tee -a "$LOG"
  issued=${PIPESTATUS[0]}
  publish "Nightly $DATE: issues of regressions"
fi
if (( issued != 0 )); then
  health --failing "opening the issues of its regressions failed."
elif [ -z "${PUSH_FAILED:-}" ]; then
  # A push that failed is reported once, at the end, unless a later push carries its commit.
  health --ok
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
  ( cd "$BENCH" && python3 charts/nightly.py page "$DATA" --hardwood "$HW" ) >> "$LOG" 2>&1
  publish "Backfill $v (${scripts:-all})"
  backfilled=1
done

# Change points once a batch of backfills is in: once, not after each, since Otava fits what
# rows there are, and a series half-way through a batch would show steps the rest fills in.
if [ -n "${backfilled:-}" ]; then
  ( cd "$BENCH" && python3 charts/nightly.py analyze "$DATA" --date "$DATE" ) 2>&1 | tee -a "$LOG"
  if (( PIPESTATUS[0] != 0 )); then
    log "analysis after the backfills failed"
    [ -n "$BACKFILL" ] || health --failing "the analysis after its backfills failed."
    log "done in $(minutes) min"
    exit 1
  else
    [ -n "$ALERT" ] || ( cd "$BENCH" && python3 charts/nightly.py mute "$DATA" ) >> "$LOG" 2>&1
    ( cd "$BENCH" && python3 charts/nightly.py page "$DATA" --hardwood "$HW" ) >> "$LOG" 2>&1
    publish "Change points after the backfills"
  fi
fi
# A commit no push carried: the last push of the run failed.
[ -z "${PUSH_FAILED:-}" ] || [ -n "$BACKFILL" ] || health --failing "pushing its results failed (see the night's log on the machine)."
log "done in $(minutes) min"
