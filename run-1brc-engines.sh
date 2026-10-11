#!/usr/bin/env bash
#
# 1BRC over Parquet across engines: Hardwood (a JVM program), the DuckDB CLI, Polars and
# PyArrow, each a fresh process per run timed by hyperfine. Not part of run-regression.sh.
# See ./run-1brc-engines.sh --help.
#
set -euo pipefail
cd "$(dirname "$0")"
source ./bench-common.sh

DUCKDB_VERSION=1.5.6
ROWS=100000000
RUNS=10
WARMUP=2
GATE_ONLY=""
PYTHON="${PYTHON:-python3}"

usage() {
  cat <<EOF
1BRC over Parquet across engines (min/mean/max of measurement per station over the file
run-1brc.sh generates). Each contender is a fresh process per run, timed by hyperfine
from start to printed result with the OS page cache warm:

  hardwood   java -cp <hardwood-core, zstd-jni, this suite's classes> OneBrc: the column
             reader by dictionary id, one row-group byte-range split per processor,
             with an AOT cache from one training run over the file
  duckdb     the DuckDB CLI $DUCKDB_VERSION, downloaded to target/engines/
  polars     engines/onebrc_polars.py   } in a venv under target/engines/, versions
  pyarrow    engines/onebrc_pyarrow.py  } pinned in engines/requirements.txt

Before timing, every contender's output is compared with the aggregate the generator
recorded next to the file; a mismatch stops the run.

Usage: ./run-1brc-engines.sh [options]
  --rows N              rows in the generated file (default $ROWS; 1000000000 for
                        the full challenge)
  --runs N              timed runs per contender (default $RUNS)
  --warmup N            untimed runs per contender first (default $WARMUP)
  --hardwood-version V  hardwood-core version to measure, as run-1brc.sh takes it
  --gate                check every contender's output, then exit (no timing)
  --help, -h            show this help

Needs hyperfine (https://github.com/sharkdp/hyperfine) and python3 with venv (PYTHON=
to pick another interpreter). Results: target/engines/1brc-<rows>.{json,md}, with the
engine, JVM and machine versions in target/engines/1brc-<rows>-versions.txt.
EOF
}

while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --rows) ROWS="${2:?--rows needs a value}"; shift 2 ;;
    --runs) RUNS="${2:?--runs needs a value}"; shift 2 ;;
    --warmup) WARMUP="${2:?--warmup needs a value}"; shift 2 ;;
    --hardwood-version) BENCH_HARDWOOD_VERSION="${2:?--hardwood-version needs a value}"; shift 2 ;;
    --gate) GATE_ONLY=1; shift ;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

command -v hyperfine > /dev/null || [ -n "$GATE_ONLY" ] \
  || { echo "hyperfine not found; install it (https://github.com/sharkdp/hyperfine#installation)" >&2; exit 1; }

OUT=target/engines
mkdir -p "$OUT"

# Hardwood: the onebrc package built as run-1brc.sh builds it. The timed program gets
# hardwood-core and zstd-jni only: the suite's class path also holds parquet-java, Hadoop and
# JMH, which the program never loads but the JVM would still search. An AOT cache accepts
# JARs only, so the suite's classes go into one.
BENCH_PACKAGE='onebrc'
bench_build
FULL_CP="$CP"
CLASSES_JAR="$OUT/onebrc-classes.jar"
rm -f "$CLASSES_JAR"
jar --create --file "$CLASSES_JAR" -C "${CP%%:*}" .
LEAN_CP="$CLASSES_JAR"
for jar in $(tr ':' '\n' <<< "${CP#*:}" | grep -E '/(hardwood-core|zstd-jni)-[^/]*\.jar$'); do
  LEAN_CP="$LEAN_CP:$jar"
done
JAVA="${JAVA:-java}"
HARDWOOD_MAIN="--enable-native-access=ALL-UNNAMED -cp $LEAN_CP dev.hardwood.benchmarks.onebrc.OneBrc"

FILE="target/onebrc_benchmark_$ROWS.parquet"
"$JAVA" --enable-native-access=ALL-UNNAMED -cp "$FULL_CP" \
  dev.hardwood.benchmarks.onebrc.MeasurementsGenerator "$FILE" "$ROWS"
EXPECTED="$FILE.expected.txt"

# The AOT cache (JEP 483, 514, 515): classes loaded and linked and method profiles from one
# training run over the measured file, rebuilt on every invocation so it always matches the
# class path. AOTMode=on fails a run whose cache does not match rather than running without it.
AOT="$OUT/onebrc-$ROWS.aot"
rm -f "$AOT"
$JAVA -XX:AOTCacheOutput="$AOT" $HARDWOOD_MAIN "$FILE" > /dev/null
HARDWOOD="$JAVA -XX:AOTMode=on -XX:AOTCache=$AOT $HARDWOOD_MAIN"

# DuckDB: the CLI release, downloaded once per version and platform.
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) duck_asset=duckdb_cli-linux-amd64.zip ;;
  Linux-aarch64) duck_asset=duckdb_cli-linux-arm64.zip ;;
  Darwin-*) duck_asset=duckdb_cli-osx-universal.zip ;;
  *) echo "No DuckDB CLI release for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
DUCKDB="$OUT/duckdb-$DUCKDB_VERSION-${duck_asset%.zip}/duckdb"
if [ ! -x "$DUCKDB" ]; then
  mkdir -p "$(dirname "$DUCKDB")"
  curl -fsSL -o "$OUT/$duck_asset" "https://github.com/duckdb/duckdb/releases/download/v$DUCKDB_VERSION/$duck_asset"
  "$PYTHON" -m zipfile -e "$OUT/$duck_asset" "$(dirname "$DUCKDB")"
  chmod +x "$DUCKDB"
  rm "$OUT/$duck_asset"
fi
# The aggregate in tenths, formatted as the other contenders print it; ORDER BY station
# compares the UTF-8 bytes, DuckDB's default collation.
SQL="$OUT/onebrc-$ROWS.sql"
cat > "$SQL" <<EOF
WITH t AS (
  SELECT station, CAST(round(measurement * 10) AS BIGINT) AS t FROM read_parquet('$FILE')
), s AS (
  SELECT station, min(t) AS mn, max(t) AS mx,
         CAST(floor((2 * sum(t) + count(*)) / (2 * count(*))::DOUBLE) AS BIGINT) AS mean
  FROM t GROUP BY station
)
SELECT '{' || string_agg(station || '='
    || CASE WHEN mn < 0 THEN '-' ELSE '' END || (abs(mn) // 10) || '.' || (abs(mn) % 10) || '/'
    || CASE WHEN mean < 0 THEN '-' ELSE '' END || (abs(mean) // 10) || '.' || (abs(mean) % 10) || '/'
    || CASE WHEN mx < 0 THEN '-' ELSE '' END || (abs(mx) // 10) || '.' || (abs(mx) % 10),
    ', ' ORDER BY station) || '}'
FROM s;
EOF
DUCK="$DUCKDB -noheader -list -f $SQL"

# Polars and PyArrow: a venv per requirements file, rebuilt when the file changes.
VENV="$OUT/venv-$(cksum < engines/requirements.txt | cut -d' ' -f1)"
if [ ! -x "$VENV/bin/python" ]; then
  "$PYTHON" -m venv "$VENV"
  "$VENV/bin/pip" install -q --disable-pip-version-check -r engines/requirements.txt
fi
POLARS="$VENV/bin/python engines/onebrc_polars.py $FILE"
PYARROW="$VENV/bin/python engines/onebrc_pyarrow.py $FILE"

NAMES=(hardwood duckdb polars pyarrow)
COMMANDS=("$HARDWOOD $FILE" "$DUCK" "$POLARS" "$PYARROW")

echo "---- Gate: every contender against $EXPECTED ----"
for i in "${!NAMES[@]}"; do
  actual="$OUT/${NAMES[i]}-$ROWS.out"
  ${COMMANDS[i]} > "$actual"
  if ! "$PYTHON" -c 'import sys; a, b = (open(p, encoding="utf-8").read().strip() for p in sys.argv[1:]); sys.exit(a != b)' \
      "$actual" "$EXPECTED"; then
    echo "${NAMES[i]} disagrees with the generator's aggregate: compare $actual with $EXPECTED" >&2
    exit 1
  fi
  echo "  ${NAMES[i]}: matches"
done
[ -n "$GATE_ONLY" ] && exit 0

VERSIONS="$OUT/1brc-$ROWS-versions.txt"
{
  echo "rows: $ROWS"
  echo "machine: $(bench_machine)"
  echo "java: $("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.runtime.version = //p')"
  echo "hardwood-core: $(tr ':' '\n' <<< "$LEAN_CP" | sed -n 's:.*/hardwood-core-\(.*\)\.jar$:\1:p')"
  echo "duckdb: $("$DUCKDB" --version)"
  "$VENV/bin/python" -c 'import polars, pyarrow; print(f"polars: {polars.__version__}\npyarrow: {pyarrow.__version__}")'
  echo "hyperfine: $(hyperfine --version)"
} | tee "$VERSIONS"

args=()
for i in "${!NAMES[@]}"; do
  args+=(-n "${NAMES[i]}" "${COMMANDS[i]}")
done
hyperfine -N --warmup "$WARMUP" --runs "$RUNS" --style basic \
  --export-json "$OUT/1brc-$ROWS.json" --export-markdown "$OUT/1brc-$ROWS.md" "${args[@]}"
