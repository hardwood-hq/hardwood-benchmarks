# Shared core for the benchmark run scripts: option parsing, build + classpath
# resolution, and the all-cores / single-core (taskset-pinned) execution passes.
# Sourced by the per-benchmark run-*.sh scripts (run-flat.sh, run-filter.sh,
# run-nested.sh) — not meant to be executed directly.
#
# A caller declares its benchmark-specific flags in BENCH_FLAGS and a help string
# in BENCH_USAGE, then calls: bench_parse_args "$@"; bench_build; bench_run <class>;
# bench_epilogue.

# Per-benchmark JVM flags, the single source of truth for the run-*.sh scripts.
# Only the flat scan's cross-engine Arrow Dataset contender
# reaches Arrow C++ over JNI, so only it needs java.nio opened, native access
# permitted, and Netty's sun.misc.Unsafe access allowed on JDK 23+. The other
# benchmarks never load Arrow and get no extra flags.
# Echoes the extra JVM flags a benchmark main class needs (empty for most).
bench_jvm_flags() {
  case "$1" in
    *FlatScanBenchmark)
      printf '%s' "--add-opens=java.base/java.nio=ALL-UNNAMED --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
      ;;
  esac
}

# Common flags every script accepts, one "flag property" pair per line. A caller
# adds its own pairs in BENCH_FLAGS; bench_flag_prop searches both. Plain
# newline-delimited strings (not associative arrays) so the harness runs on bash
# 3.2 too, e.g. stock macOS /bin/bash.
BENCH_COMMON_FLAGS='--warmup perf.warmup
--meas perf.meas
--forks perf.forks
--prof perf.prof
--include perf.include
--time perf.time'

# Resolve a long flag (e.g. --start) to its -Dperf.* property, searching the
# caller's BENCH_FLAGS first, then BENCH_COMMON_FLAGS. Echoes the property and
# returns 0 on a hit; returns 1 with no output otherwise.
bench_flag_prop() {
  local name="$1" k v
  while read -r k v; do
    [ -n "$k" ] || continue
    if [ "$k" = "$name" ]; then
      printf '%s' "$v"
      return 0
    fi
  done <<EOF
${BENCH_FLAGS:-}
${BENCH_COMMON_FLAGS:-}
EOF
  return 1
}

# Help text for the common flags; each script appends it to its own BENCH_USAGE.
BENCH_COMMON_USAGE="  --warmup N        JMH warmup iterations (default 3)
  --meas N          JMH measurement iterations (default 5)
  --forks N         JMH forks (default 1; 0 = in-process, non-forked debug run)
  --prof LIST       JMH profilers, e.g. gc,stack
  --include REGEX   restrict to benchmark methods matching REGEX
  --time S          seconds per JMH warmup and measurement iteration (default 2)
  --machine LABEL   hardware label recorded in the meta sidecar for the chart
                    subtitle (default: auto-detected CPU model + core count). The
                    chart derives all-cores vs single-core from the pass itself.
  --hardwood-version V
                    hardwood-core version to resolve and measure, overriding
                    <hardwood.version> in pom.xml (e.g. 1.0.0.CR1). A released
                    version comes from Maven Central; a -SNAPSHOT must be
                    installed locally first. The version actually resolved is
                    recorded in the meta sidecar, so compare-runs.py can check
                    that two snapshots really do differ.
  --no-pin          all-cores pass only (skip the taskset-pinned single-core pass)
  --pin-only        taskset-pinned single-core pass only (skip the all-cores pass);
                    handy for 1-core profiling. Needs taskset (Linux).
  --gate            gate-check mode: verify every contender agrees, then exit
                    (no JMH, no timing, no results file)
  --regression      quick, fixed regression configuration: the benchmark's
                    preset sizes and contenders (listed below), 3 warmup and 3
                    measurement iterations of 1 s unless its preset says
                    otherwise, and one pass, on all cores.
                    Regression runs taken at any time are then alike; passing a
                    flag the preset sets is an error. The meta sidecar records
                    the preset.
  --help, -h        show this help
Any -Dperf.*=… (or other -D…) is also passed straight through to the JVM."

# Parse the caller's argv into the global ARGS array (the -D… list handed to
# java) and the PERF_PIN env. Recognized long flags — the caller's BENCH_FLAGS
# plus BENCH_COMMON_FLAGS — become -Dperf.*; --no-pin sets PERF_PIN=0; --help
# prints BENCH_USAGE; any -D… or unrecognized token passes through untouched.
# Accepts both `--flag value` and `--flag=value`.
bench_parse_args() {
  ARGS=()
  while (( $# )); do
    case "$1" in
      -h|--help)
        printf '%s\n' "$BENCH_USAGE"
        exit 0
        ;;
      --no-pin)
        PERF_PIN=0
        shift
        ;;
      --pin-only)
        BENCH_PIN_ONLY=1
        shift
        ;;
      --gate)
        BENCH_GATE=1
        shift
        ;;
      --regression)
        BENCH_REGRESSION=1
        shift
        ;;
      --machine)
        if (( $# < 2 )); then
          echo "Option --machine needs a value (try --help)" >&2
          exit 2
        fi
        BENCH_MACHINE="$2"
        shift 2
        ;;
      --machine=*)
        BENCH_MACHINE="${1#*=}"
        shift
        ;;
      --hardwood-version)
        if (( $# < 2 )); then
          echo "Option --hardwood-version needs a value (try --help)" >&2
          exit 2
        fi
        BENCH_HARDWOOD_VERSION="$2"
        shift 2
        ;;
      --hardwood-version=*)
        BENCH_HARDWOOD_VERSION="${1#*=}"
        shift
        ;;
      --*)
        local name="${1%%=*}"
        local prop="$(bench_flag_prop "$name")"
        if [[ -z "$prop" ]]; then
          echo "Unknown option: $name (try --help)" >&2
          exit 2
        fi
        if [[ "$1" == *=* ]]; then
          ARGS+=("-D$prop=${1#*=}")
          shift
        else
          if (( $# < 2 )); then
            echo "Option $name needs a value (try --help)" >&2
            exit 2
          fi
          ARGS+=("-D$prop=$2")
          shift 2
        fi
        ;;
      *)
        ARGS+=("$1")
        shift
        ;;
    esac
  done

  if [[ -n "${BENCH_REGRESSION:-}" ]]; then
    bench_apply_regression_preset
  fi

  # Tee everything printed from here on (build + passes + epilogue) to a
  # per-benchmark log under target/, so a run's full console output is archived
  # next to its TSVs and meta sidecar; capture-run.sh then snapshots the lot. The
  # log name comes from the run script (run-flat.sh -> target/flat.log). Set
  # BENCH_LOG=0 to skip. On exit, bench_exit closes the fds and waits for the tee
  # child so the tail is never lost to the copy.
  if [[ "${BENCH_LOG:-1}" != 0 ]]; then
    local base="${0##*/}"; base="${base#run-}"; base="${base%.sh}"
    mkdir -p target
    exec > >(tee "target/${base}.log") 2>&1
    BENCH_LOG_OPEN=1
    trap bench_exit EXIT
  fi
}

# Registers a command for the script to run when it exits. Commands run in the
# order registered, before the log is closed, so their output reaches the log. A
# script uses this instead of its own EXIT trap, which would replace the log's.
BENCH_ON_EXIT=()
bench_on_exit() {
  BENCH_ON_EXIT+=("$1")
  trap bench_exit EXIT
}

# The EXIT trap: the registered commands, then the log's fds closed and the tee
# child waited for. A bare `wait` (rather than `wait $!`) keeps this correct on
# bash 3.2, where $! does not reflect a process substitution.
bench_exit() {
  local c
  for c in "${BENCH_ON_EXIT[@]+"${BENCH_ON_EXIT[@]}"}"; do
    eval "$c"
  done
  if [[ -n "${BENCH_LOG_OPEN:-}" ]]; then
    exec 1>&- 2>&-
    wait 2>/dev/null || true
  fi
}

# Apply the caller's BENCH_REGRESSION_PRESET (its size flags, in the same
# `--flag value` form a user would pass) to ARGS. A size flag given alongside
# --regression would make the run read different data from every other
# regression run, so it is an error rather than an override. A benchmark with one
# size (a regression-only one) declares an empty preset.
bench_apply_regression_preset() {
  # The iteration counts every benchmark shares, unless the script's own preset sets one: a
  # benchmark whose operations need longer to settle (run-s3.sh) raises its warmup there.
  local common=(--warmup 3 --meas 3 --time 1) own=(${BENCH_REGRESSION_PRESET:-}) preset=() i j prop a overridden
  for (( i = 0; i < ${#common[@]}; i += 2 )); do
    overridden=""
    for (( j = 0; j < ${#own[@]}; j += 2 )); do
      if [[ "${own[j]}" == "${common[i]}" ]]; then
        overridden=1
      fi
    done
    if [[ -z "$overridden" ]]; then
      preset+=("${common[i]}" "${common[i+1]}")
    fi
  done
  preset+=("${own[@]+"${own[@]}"}")
  for (( i = 0; i < ${#preset[@]}; i += 2 )); do
    prop="$(bench_flag_prop "${preset[i]}")" || { echo "Preset flag ${preset[i]} is not a flag of this script" >&2; exit 2; }
    for a in "${ARGS[@]+"${ARGS[@]}"}"; do
      if [[ "$a" == "-D$prop="* ]]; then
        echo "--regression fixes ${preset[i]} (to ${preset[i+1]}); drop ${preset[i]} or --regression" >&2
        exit 2
      fi
    done
    ARGS+=("-D$prop=${preset[i+1]}")
  done
  # The contenders: the script's BENCH_REGRESSION_INCLUDE, its Hardwood paths plus
  # at most one control (a contender pinned in the pom, whose drift says the machine
  # moved), so no path is timed twice under different parameters.
  for a in "${ARGS[@]+"${ARGS[@]}"}"; do
    if [[ "$a" == -Dperf.include=* ]]; then
      echo "--regression fixes the contenders (to '${BENCH_REGRESSION_INCLUDE:-hardwood}'); drop --include or --regression" >&2
      exit 2
    fi
  done
  # One pass, on all cores. Pinned to one core, the JIT, the GC and the reader's
  # worker threads share that core, and a contender is still ~15% off its steady
  # state after ten 1 s iterations; on all cores it settles by the third.
  PERF_PIN=0
  ARGS+=("-Dperf.include=${BENCH_REGRESSION_INCLUDE:-hardwood}")
}

# Per-benchmark machine-readable throughput. In benchmark mode bench_run writes
# target/bench-throughput-<Benchmark>.tsv (one file per benchmark, so separate
# runs don't clobber each other), pivoted into a chart-ready table by
# bench_epilogue; the per-benchmark make-*-chart.py generators read them. Set per run by bench_run.
BENCH_RESULTS=""

# Compile and resolve the runtime classpath into $CP. Call once per run.
#
# --hardwood-version reaches Maven here and nowhere else: hardwood.version is a
# pom property, so it has to be set on these two invocations rather than passed
# through to the JVM like the -Dperf.* flags. A -D on the run script's own command
# line lands on `java`, where nothing reads it.
bench_build() {
  local mvn_args=()
  [ -n "${BENCH_HARDWOOD_VERSION:-}" ] && mvn_args+=("-Dhardwood.version=$BENCH_HARDWOOD_VERSION")

  # Compile only the shared classes plus the caller's package (BENCH_PACKAGE), so
  # a benchmark using API the requested version lacks fails its own build and no
  # other.
  local package=${BENCH_PACKAGE:?"BENCH_PACKAGE must name the script's source package"}
  mvn_args+=("-Dbench.include=dev/hardwood/benchmarks/$package/**/*.java")

  # Each Hardwood version and package builds into a directory of its own under
  # target/build/, so classes compiled against one version never mix with another's,
  # and a run that switches back to a version reuses its build instead of redoing
  # it (two Maven invocations, the bulk of a short run's overhead). A build is
  # reused while no source file or the pom is newer than it, and while the resolved
  # hardwood-core jar is not either: reinstalling a -SNAPSHOT at another commit
  # rebuilds. The generated fixtures and downloaded corpora under target/ are
  # untouched.
  local version="${BENCH_HARDWOOD_VERSION:-$(sed -n 's:.*<hardwood.version>\(.*\)</hardwood.version>.*:\1:p' pom.xml | head -1)}"
  local dir="target/build/${version}_$package"
  local jar="$HOME/.m2/repository/dev/hardwood/hardwood-core/$version/hardwood-core-$version.jar"
  if [ -f "$dir/cp.txt" ] && [ -f "$dir/.built" ] \
      && [ -z "$(find src pom.xml -newer "$dir/.built" -print -quit)" ] \
      && ! { [ -f "$jar" ] && [ "$jar" -nt "$dir/.built" ]; }; then
    CP="$dir/classes:$(cat "$dir/cp.txt")"
    return
  fi
  rm -rf "$dir"
  mvn_args+=("-Dbench.outputDirectory=$PWD/$dir/classes" "-Dbench.generatedSources=$PWD/$dir/generated-sources")

  if ! ./mvnw -q -ntp "${mvn_args[@]+"${mvn_args[@]}"}" compile; then
    if [ -n "${BENCH_HARDWOOD_VERSION:-}" ]; then
      echo >&2
      echo "Build failed against hardwood-core $BENCH_HARDWOOD_VERSION." >&2
      echo "The benchmark sources track the current API, so a benchmark using something" >&2
      echo "that version does not have will not compile — see the errors above for which." >&2
      echo "Only this benchmark is affected; the others build their own packages." >&2
    fi
    rm -rf "$dir"
    exit 1
  fi
  ./mvnw -q -ntp "${mvn_args[@]+"${mvn_args[@]}"}" dependency:build-classpath -Dmdep.outputFile="$PWD/$dir/cp.txt"
  touch "$dir/.built"
  CP="$dir/classes:$(cat "$dir/cp.txt")"
}

# Run one benchmark main class. Two modes:
#   --gate  → gate-check only: verify every contender agrees (-Dperf.gate=true),
#             then exit. No JMH, no passes, no results file.
#   default → benchmark: two timed passes, no gate —
#               unpinned (all cores): every contender;
#               taskset -c 0 (single core): only the Hardwood contenders (the
#                 single-threaded baselines don't change when pinned, so re-timing
#                 them on the slow single-core pass is wasted).
# Benchmark mode writes a per-benchmark results file and tags each pass via
# -Dperf.pass. The bench-managed -D flags go before "${ARGS[@]}" so a user-supplied
# --include / -D can override them — except the pinned pass's -Dperf.include=hardwood,
# which is placed *after* ARGS so the single-core pass stays Hardwood-only regardless
# of a user --include (a publish-run --include then only narrows the all-cores pass,
# e.g. to drop the non-published Arrow / SpecificRecord contenders). $flags is
# intentionally unquoted (word-split).
# Hardware label for the current run — the CPU model and core count. Hardware
# only: the all-cores vs single-core distinction is the chart's to derive from the
# `pass` column, so this one label serves both passes. Overridden by --machine
# (BENCH_MACHINE); otherwise auto-detected from lscpu, falling back to uname.
bench_machine() {
  if [[ -n "${BENCH_MACHINE:-}" ]]; then
    printf '%s' "$BENCH_MACHINE"
    return
  fi
  local model cores
  model="$(lscpu 2>/dev/null | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
  # lscpu prints "-" for an unknown model (common in VMs/containers); fall back.
  [[ -n "$model" && "$model" != "-" ]] || model="$(uname -sm)"
  cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo '?')"
  printf '%s (%s cores)' "$model" "$cores"
}

bench_run() {
  local main="$1"
  local flags="$(bench_jvm_flags "$main")"
  local name="${main##*.}"

  echo
  echo "######################################################################"
  echo "## $name"
  echo "######################################################################"

  # Wall-clock the whole bench (data load + both passes), separate from JMH's
  # per-pass "Run complete. Total time".
  local start_epoch
  start_epoch=$(date +%s)
  echo "Started $name at $(date '+%Y-%m-%d %H:%M:%S %Z')"

  if [[ -n "${BENCH_GATE:-}" ]]; then
    echo "---- Gate check (correctness only — no benchmarking) ----"
    java $flags -Dperf.gate=true "${ARGS[@]+"${ARGS[@]}"}" -cp "$CP" "$main"
  else
    BENCH_RESULTS="target/bench-throughput-$name.tsv"
    printf 'pass\tbenchmark\tcontender\tms_per_op\tm_rows_per_s\tmb_per_s\n' > "$BENCH_RESULTS"

    if [[ -z "${BENCH_PIN_ONLY:-}" ]]; then
      echo "---- Out-of-the-box (all cores) ----"
      java $flags -Dperf.results="$BENCH_RESULTS" -Dperf.pass=unpinned \
        "${ARGS[@]+"${ARGS[@]}"}" -cp "$CP" "$main"
    fi

    if [[ "${PERF_PIN:-1}" != 0 && "${BENCH_SINGLE_CORE:-1}" != 0 ]] && command -v taskset >/dev/null 2>&1; then
      echo
      echo "---- Pinned to a single core (taskset -c 0) — Hardwood contenders only ----"
      taskset -c 0 java $flags -Dperf.results="$BENCH_RESULTS" -Dperf.pass=pinned \
        "${ARGS[@]+"${ARGS[@]}"}" \
        -Dperf.include=hardwood -cp "$CP" "$main"
    elif [[ -n "${BENCH_PIN_ONLY:-}" ]]; then
      echo "(--pin-only set but the pinned pass can't run: $(command -v taskset >/dev/null 2>&1 && echo 'PERF_PIN=0' || echo 'taskset not found'). Nothing ran.)" >&2
    fi

    # Append the run's hardware label to the meta sidecar the benchmark just wrote
    # (bench-meta-* beside bench-throughput-*). The harness owns this — the JVM
    # writes the dataset/java/hardwood keys, the capture script adds where it ran.
    local meta="${BENCH_RESULTS/bench-throughput-/bench-meta-}"
    if [[ -f "$meta" ]]; then
      printf 'machine\t%s\n' "$(bench_machine)" >> "$meta"
      printf 'preset\t%s\n' "$([[ -n "${BENCH_REGRESSION:-}" ]] && echo regression || echo none)" >> "$meta"
    fi
  fi

  local secs=$(( $(date +%s) - start_epoch ))
  printf 'Finished %s at %s (duration %dh %02dm %02ds)\n' \
    "$name" "$(date '+%Y-%m-%d %H:%M:%S %Z')" $((secs / 3600)) $(((secs % 3600) / 60)) $((secs % 60))
}

# Pivot the accumulated results into a chart-ready table and ASCII chart, then
# print the once-per-run pinning note. Full scans emit throughput (M rows/s,
# higher is better); the filtered scan emits ms/op (lower is better). The chart
# is chosen from whether column 5 (M rows/s) carries a value or "-".
bench_epilogue() {
  [[ -n "${BENCH_GATE:-}" ]] && return  # gate-check mode produces no results
  if [[ -s "${BENCH_RESULTS:-/nonexistent}" ]] && [[ "$(wc -l < "$BENCH_RESULTS")" -gt 1 ]]; then
    if awk -F'\t' 'NR>1 && $5!="-"{f=1} END{exit !f}' "$BENCH_RESULTS"; then
      bench_epilogue_throughput
    else
      bench_epilogue_msop
    fi
  fi

  if [[ "${BENCH_SINGLE_CORE:-1}" == 0 ]]; then
    : # benchmark has no single-core pass by design (Hardwood-only, all cores) — no note
  elif [[ "${PERF_PIN:-1}" == 0 ]]; then
    echo
    echo "(--no-pin: ran the all-cores pass only, skipped the single-core pass.)"
  elif ! command -v taskset >/dev/null 2>&1; then
    echo
    echo "(taskset not found: skipped the single-core pass. It is Linux-only;"
    echo " macOS has no per-process CPU affinity, so run on a Linux host for"
    echo " engine-for-engine per-core numbers.)"
  fi
}

# Throughput chart (M rows/s, higher is better) — the flat/nested full scans.
bench_epilogue_throughput() {
    echo
    echo "====================================================================="
    echo "Chart input — throughput by contender (raw TSV: $BENCH_RESULTS)"
    echo "Only Hardwood is timed pinned. The single-threaded baselines show '-' for"
    echo "1-core: not separately measured — pinning doesn't change a single-threaded"
    echo "reader, so use their default value as the per-core number too."
    echo "MB/s is on-disk (compressed) bytes / time — a full-scan read rate, not a decode rate."
    echo "====================================================================="
    awk -F'\t' '
      NR==1 { next }
      { k=$2 SUBSEP $3; if(!(k in seen)){seen[k]=1; ord[++n]=k; b[k]=$2; c[k]=$3} }
      $1=="unpinned" { def[k]=$5; defmb[k]=$6 }
      $1=="pinned"   { pin[k]=$5; pinmb[k]=$6 }
      END {
        printf "%-20s %-26s %11s %11s %9s %9s\n","benchmark","contender","default","1-core","def MB/s","1c MB/s"
        printf "%-20s %-26s %11s %11s %9s %9s\n","","","M rows/s","M rows/s","",""
        for(i=1;i<=n;i++){ k=ord[i];
          d =(k in def)  ? def[k]   : "-";
          dm=(k in defmb)? defmb[k] : "-";
          p =(k in pin)  ? pin[k]   : "-";
          pm=(k in pinmb)? pinmb[k] : "-";
          printf "%-20s %-26s %11s %11s %9s %9s\n", b[k], c[k], d, p, dm, pm }
      }' "$BENCH_RESULTS"

    # ASCII charts plot the default (all-cores) numbers; skip them on a pin-only run.
    if awk -F'\t' '$1=="unpinned"{f=1} END{exit !f}' "$BENCH_RESULTS"; then
    echo
    echo "ASCII charts — default (out-of-box) throughput, M rows/s; 1-core shown where measured"
    awk -F'\t' '
      function bar(v, mx,   n,i,s){ n=(mx>0)?int(v/mx*30+0.5):0; s=""; for(i=0;i<n;i++) s=s "█"; return s }
      NR==1 { next }
      {
        bench=$2; cont=$3; v=$5+0
        if (cont ~ /Columnar/ || cont=="arrowDataset") ct="columnar (raw decode)"
        else if (cont ~ /RowReader/ || cont ~ /vroParquetReader/) ct="record (materialization)"
        else next
        k=bench SUBSEP ct
        if ($1=="unpinned") {
          if (!((k,cont) in have)) { have[k,cont]=1; cnt[k]++; ord[k,cnt[k]]=cont }
          def[k,cont]=v; if (v>mx[k]) mx[k]=v
          if (!(k in seenk)) { seenk[k]=1; ko[++nk]=k; kbench[k]=bench; kct[k]=ct }
        } else if ($1=="pinned") { pin[k,cont]=v }
      }
      END {
        for (j=1;j<=nk;j++) {
          k=ko[j]
          printf "\n  %s — %s\n", kbench[k], kct[k]
          for (t=1;t<=cnt[k];t++) {
            c=ord[k,t]; v=def[k,c]
            line=sprintf("    %-26s│%s %.1f", c, bar(v,mx[k]), v)
            if ((k,c) in pin) line=line sprintf("   (1-core %.1f)", pin[k,c])
            print line
          }
        }
      }' "$BENCH_RESULTS"
    fi
}

# ms/op chart (lower is better) — the filtered scan, whose throughput denominator
# (rows-returned / surviving-page bytes) is selectivity-dependent and so not
# auto-derived. JMH params are folded into the contender label.
bench_epilogue_msop() {
    local has_pinned=0
    grep -q "^pinned" "$BENCH_RESULTS" && has_pinned=1
    echo
    echo "====================================================================="
    echo "Chart input — ms/op by contender (raw TSV: $BENCH_RESULTS) · lower is better"
    [[ "$has_pinned" == 1 ]] && echo "Only Hardwood is timed pinned; single-threaded baselines show '-' for 1-core."
    echo "====================================================================="
    awk -F'\t' -v pinned="$has_pinned" '
      NR==1 { next }
      { k=$2 SUBSEP $3; if(!(k in seen)){seen[k]=1; ord[++n]=k; b[k]=$2; c[k]=$3} }
      $1=="unpinned" { def[k]=$4 }
      $1=="pinned"   { pin[k]=$4 }
      END {
        if (pinned) {
          printf "%-18s %-30s %11s %11s\n","benchmark","contender","default","1-core"
          printf "%-18s %-30s %11s %11s\n","","","ms/op","ms/op"
          for(i=1;i<=n;i++){ k=ord[i]; d=(k in def)?def[k]:"-"; p=(k in pin)?pin[k]:"-";
            printf "%-18s %-30s %11s %11s\n", b[k], c[k], d, p }
        } else {
          printf "%-18s %-30s %11s\n","benchmark","contender","ms/op"
          for(i=1;i<=n;i++){ k=ord[i]; d=(k in def)?def[k]:"-";
            printf "%-18s %-30s %11s\n", b[k], c[k], d }
        }
      }' "$BENCH_RESULTS"

    if awk -F'\t' '$1=="unpinned"{f=1} END{exit !f}' "$BENCH_RESULTS"; then
    echo
    if [[ "$has_pinned" == 1 ]]; then
      echo "ASCII chart — ms/op (lower is better), default (out-of-box); 1-core shown where measured"
    else
      echo "ASCII chart — ms/op (lower is better)"
    fi
    awk -F'\t' '
      function bar(v, mx,   m,i,s){ m=(mx>0)?int(v/mx*30+0.5):0; s=""; for(i=0;i<m;i++) s=s "█"; return s }
      NR==1 { next }
      {
        bench=$2; cont=$3; v=$4+0
        grp=bench
        if (match(cont, /\[[^]]*\]/)) { grp=bench " — " substr(cont,RSTART+1,RLENGTH-2); cont=substr(cont,1,RSTART-1) }
        if ($1=="unpinned") {
          if (!((grp,cont) in have)) { have[grp,cont]=1; cnt[grp]++; ord[grp,cnt[grp]]=cont }
          def[grp,cont]=v; if (v>mx[grp]) mx[grp]=v
          if (!(grp in seenk)) { seenk[grp]=1; ko[++nk]=grp }
        } else if ($1=="pinned") { pin[grp,cont]=v }
      }
      END {
        for (j=1;j<=nk;j++) {
          grp=ko[j]
          printf "\n  %s\n", grp
          for (t=1;t<=cnt[grp];t++) {
            c=ord[grp,t]; v=def[grp,c]
            line=sprintf("    %-22s│%s %.1f", c, bar(v,mx[grp]), v)
            if ((grp,c) in pin) line=line sprintf("   (1-core %.1f)", pin[grp,c])
            print line
          }
        }
      }' "$BENCH_RESULTS"
    fi
}
