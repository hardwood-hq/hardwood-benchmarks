# Shared core for the benchmark run scripts: option parsing, build + classpath
# resolution, and the all-cores / single-core (taskset-pinned) execution passes.
# Sourced by the per-benchmark run-*.sh scripts; not meant to be executed directly.
#
# A caller declares its benchmark-specific flags in BENCH_FLAGS and a help string
# in BENCH_USAGE, then calls: bench_parse_args "$@"; bench_build; bench_run <class>;
# bench_epilogue.

# The heap and collector every benchmark JVM of a regression run uses (bench_run), recorded in
# a regression run's manifest.
BENCH_HEAP_FLAGS="-Xms4g -Xmx4g -XX:+UseG1GC"
export BENCH_HEAP_FLAGS

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
  # The iteration counts and forks every benchmark shares, unless the script's own preset sets
  # one: each script warms its contenders for as long as they took to settle on an Intel N300
  # at 1.50 GHz. Three forks show a JIT decision that differs between JVMs instead of averaging
  # it away. The `quick` profile (run-regression.sh --profile quick) adds the script's
  # BENCH_QUICK_PRESET and times its BENCH_QUICK_INCLUDE contenders only.
  local common=(--warmup 3 --meas 3 --time 1 --forks 3) own=(${BENCH_REGRESSION_PRESET:-}) preset=() i j prop a overridden
  if [[ "${BENCH_PROFILE:-}" == quick ]]; then
    own+=(${BENCH_QUICK_PRESET:-})
  fi
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
    # A smoke run (run-regression.sh --smoke) checks that each contender runs, not its time.
    case "${BENCH_SMOKE:-}:${preset[i]}" in
      1:--warmup) ARGS+=("-D$prop=0") ;;
      1:--meas|1:--time|1:--forks) ARGS+=("-D$prop=1") ;;
      *) ARGS+=("-D$prop=${preset[i+1]}") ;;
    esac
  done
  # The contenders: the script's BENCH_REGRESSION_INCLUDE, or in the quick profile its
  # BENCH_QUICK_INCLUDE, each a path no other contender covers.
  local include="${BENCH_REGRESSION_INCLUDE:-hardwood}"
  [[ "${BENCH_PROFILE:-}" == quick ]] && include="${BENCH_QUICK_INCLUDE:?run-${0##*/} has no quick contenders}"
  for a in "${ARGS[@]+"${ARGS[@]}"}"; do
    case "$a" in
      -Dperf.include=*|-Dperf.prof=*)
        echo "--regression fixes the contenders and profiler; drop --include, --prof or --regression" >&2
        exit 2 ;;
    esac
  done
  # Each fork records what RegressionProfiler measures besides time; bench_run names its file
  # and fixes the benchmark JVMs' heap.
  ARGS+=("-Dperf.prof=dev.hardwood.benchmarks.RegressionProfiler")
  # One pass, on all cores. Pinned to one core, the JIT, the GC and the reader's
  # worker threads share that core, and a contender is still ~15% off its steady
  # state after ten 1 s iterations; on all cores it settles by the third.
  PERF_PIN=0
  ARGS+=("-Dperf.include=$include")
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
#
# Each Hardwood version builds once, into target/build/<version>/, and every script run
# against it reuses that build: its classpath, the version adapters bench_compat picked for
# it, and the classes of the shared code and of every package whose REQUIRES the version
# meets (see src/compat/README.md). A build is reused while no source file or the pom is
# newer than it, and while the resolved hardwood-core jar is not either: reinstalling a
# -SNAPSHOT at another commit rebuilds. The generated fixtures and downloaded corpora under
# target/ are untouched. A script whose package (BENCH_PACKAGE) requires a feature the
# version predates prints that it is not supported and exits 0.
bench_build() {
  local package=${BENCH_PACKAGE:?"BENCH_PACKAGE must name the script's source package"}
  local version="${BENCH_HARDWOOD_VERSION:-$(sed -n 's:.*<hardwood.version>\(.*\)</hardwood.version>.*:\1:p' pom.xml | head -1)}"
  local jar="$HOME/.m2/repository/dev/hardwood/hardwood-core/$version/hardwood-core-$version.jar"
  local dir="target/build/$version"
  if ! bench_build_current "$dir/.built" "$jar"; then
    local mvn_args=()
    [ -n "${BENCH_HARDWOOD_VERSION:-}" ] && mvn_args+=("-Dhardwood.version=$BENCH_HARDWOOD_VERSION")
    rm -rf "$dir"
    mkdir -p "$dir"
    ./mvnw -q -ntp "${mvn_args[@]+"${mvn_args[@]}"}" dependency:build-classpath -Dmdep.outputFile="$PWD/$dir/cp.txt" \
      || { rm -rf "$dir"; exit 1; }
    bench_compat "$version" "$dir" "$(cat "$dir/cp.txt")"
    printf '%s' "$BENCH_UNSUPPORTED" > "$dir/unsupported.txt"
    bench_sources "$dir"
    mvn_args+=("-Dbench.sources=$PWD/$dir/src" "-Dbench.compat.sources=$PWD/$dir/compat/src"
               "-Dbench.outputDirectory=$PWD/$dir/classes" "-Dbench.generatedSources=$PWD/$dir/generated-sources")
    if ! ./mvnw -q -ntp "${mvn_args[@]+"${mvn_args[@]}"}" compile; then
      echo >&2
      echo "Build failed against hardwood-core $version." >&2
      echo "A benchmark reaches API that differs between Hardwood versions through an adapter" >&2
      echo "in src/compat, and names a feature older versions lack in its package's REQUIRES;" >&2
      echo "see src/compat/README.md and the errors above." >&2
      rm -rf "$dir"
      exit 1
    fi
    touch "$dir/.built"
  fi
  BENCH_UNSUPPORTED="$(cat "$dir/unsupported.txt")"
  bench_check_requires "$version" "$package" || exit 0
  CP="$dir/classes:$(cat "$dir/cp.txt")"
}

# Whether the build stamped by marker $1 is current: it exists, and neither a source file,
# the pom nor the resolved hardwood-core jar $2 is newer.
bench_build_current() {
  [ -f "$1" ] \
    && [ -z "$(find src pom.xml -newer "$1" -print -quit)" ] \
    && ! { [ -f "$2" ] && [ "$2" -nt "$1" ]; }
}

# The features package $1 cannot run without, from its REQUIRES file (none without one).
bench_requires() {
  local file="src/main/java/dev/hardwood/benchmarks/$1/REQUIRES"
  [ -f "$file" ] && cat "$file"
  return 0
}

# Echoes the first feature package $1 requires that the version predates, as
# `<feature>@<since>` from BENCH_UNSUPPORTED; returns 1 when there is none.
bench_package_unsupported() {
  local feature entry
  for feature in $(bench_requires "$1"); do
    for entry in ${BENCH_UNSUPPORTED//,/ }; do
      if [[ "${entry%@*}" == "$feature" ]]; then
        printf '%s' "$entry"
        return 0
      fi
    done
  done
  return 1
}

# Assemble $1/src, the tree the version's build compiles: the shared classes, and every
# package whose REQUIRES the version meets.
bench_sources() {
  local root="src/main/java/dev/hardwood/benchmarks" out="$1/src/dev/hardwood/benchmarks" pkg
  mkdir -p "$out"
  cp "$root"/*.java "$out/"
  for pkg in "$root"/*/; do
    pkg="$(basename "$pkg")"
    bench_package_unsupported "$pkg" > /dev/null || cp -R "$root/$pkg" "$out/"
  done
}

# Return 1, after saying so, when package $2 requires a feature Hardwood $1 predates;
# otherwise pass the unsupported features to the JVM, where Features lets a benchmark leave
# out the contenders or parameter values that need them.
bench_check_requires() {
  local entry
  if entry="$(bench_package_unsupported "$2")"; then
    echo "Not supported by hardwood-core $1: ${0##*/} needs ${entry%@*} (from ${entry#*@})"
    return 1
  fi
  [ -n "$BENCH_UNSUPPORTED" ] && ARGS+=("-Dbench.unsupported=$BENCH_UNSUPPORTED")
  return 0
}

# Pick the version adapters for Hardwood $1 into $2/compat/src, given its classpath $3, and
# set BENCH_UNSUPPORTED to the features it predates (`<feature>@<since>`, comma-separated).
# Each directory src/compat/<concern> but `current` is a concern: its candidates, tried in
# order with javac, are the adapter for the current API (src/compat/current/.../compat/<concern>),
# its numbered directories (adapters for older APIs, newest first) and its probe/ (a feature
# with no adapter). The first that compiles is the one the version has. When none does and
# the concern has a `since` the version predates, the feature is unsupported; otherwise the
# version has an API no adapter covers, and the build stops.
bench_compat() {
  local version="$1" dir="$2" cp="$3" out="$2/compat" javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
  local concern name candidate chosen since missing=()
  mkdir -p "$out/src" "$out/probe"
  BENCH_UNSUPPORTED=""
  for concern in src/compat/*/; do
    name="$(basename "$concern")"
    [ "$name" = current ] && continue
    local candidates=()
    [ -d "src/compat/current/dev/hardwood/benchmarks/compat/$name" ] \
      && candidates+=("src/compat/current/dev/hardwood/benchmarks/compat/$name")
    for candidate in "$concern"[0-9]*/ "$concern"probe/; do
      [ -d "$candidate" ] && candidates+=("${candidate%/}")
    done
    # A concern with no candidate is behaviour no compile can detect: its `since` alone decides it.
    if (( ${#candidates[@]} == 0 )); then
      if bench_version_before "$version" "$(cat "${concern}since")"; then
        BENCH_UNSUPPORTED+="${BENCH_UNSUPPORTED:+,}$name@$(cat "${concern}since")"
      fi
      continue
    fi
    chosen=""
    for candidate in "${candidates[@]+"${candidates[@]}"}"; do
      if "$javac" --release 21 -proc:none -nowarn -d "$out/probe/$name" -cp "$cp" "$candidate"/*.java \
          > "$out/probe/$name-$(basename "$candidate").log" 2>&1; then
        chosen="$candidate"
        break
      fi
    done
    if [ -n "$chosen" ]; then
      if [ "$(basename "$chosen")" != probe ]; then
        mkdir -p "$out/src/dev/hardwood/benchmarks/compat/$name"
        cp "$chosen"/*.java "$out/src/dev/hardwood/benchmarks/compat/$name/"
      fi
    elif [ -f "${concern}since" ] && bench_version_before "$version" "$(cat "${concern}since")"; then
      since="$(cat "${concern}since")"
      BENCH_UNSUPPORTED+="${BENCH_UNSUPPORTED:+,}$name@$since"
    else
      missing+=("$name")
    fi
  done
  if (( ${#missing[@]} )); then
    echo "No adapter in src/compat matches hardwood-core $version for: ${missing[*]}." >&2
    echo "Either the version has an API none of the adapters covers (add one), or it predates" >&2
    echo "a feature whose src/compat/<concern>/since says otherwise. javac's output per" >&2
    echo "candidate is in $out/probe/." >&2
    rm -rf "$dir"
    exit 1
  fi
}

# Order key of a Hardwood version: X.Y.Z, then Alpha < Beta < CR < any -SNAPSHOT of X.Y.Z
# < Final, then the qualifier's number. A -SNAPSHOT sorts after every pre-release of its
# version, so it counts as having each feature released before X.Y.Z.Final.
bench_version_key() {
  local v="$1" rank num
  if [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)-(.+-)?SNAPSHOT$ ]]; then
    rank=4
    num=0
  elif [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)\.(Alpha|Beta|CR|Final)([0-9]*)$ ]]; then
    case "${BASH_REMATCH[4]}" in
      Alpha) rank=1 ;;
      Beta) rank=2 ;;
      CR) rank=3 ;;
      Final) rank=5 ;;
    esac
    num="${BASH_REMATCH[5]:-0}"
  else
    echo "Cannot order Hardwood version '$v'" >&2
    exit 2
  fi
  printf '%05d%05d%05d%d%05d' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" "$rank" "$num"
}

# Whether Hardwood version $1 is older than $2.
bench_version_before() {
  local a b
  a="$(bench_version_key "$1")" || exit 2
  b="$(bench_version_key "$2")" || exit 2
  [[ "$a" < "$b" ]]
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
    if [[ -n "${BENCH_REGRESSION:-}" ]]; then
      rm -f "target/bench-metrics-$name.jsonl"
      ARGS+=("-Dperf.metrics=$PWD/target/bench-metrics-$name.jsonl")
      # A heap of fixed size and collector in the benchmark JVMs (the main and its forks, which
      # inherit the environment), so that the live-set peak is measured alike in every run and
      # a retention regression meets a collection.
      export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$BENCH_HEAP_FLAGS"
    fi

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
