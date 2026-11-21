# Version adapters and features

The benchmarks build against every Hardwood version from 1.0.0.Final on. Where the API
differs between versions, a benchmark calls an adapter in `dev.hardwood.benchmarks.compat`
instead of Hardwood directly; where an older version lacks a feature, the script, contender
or parameter value that needs it does not run on that version and is reported as not
supported.

## Layout

Each directory here but `current` is a *concern*:

| Path | Holds |
| --- | --- |
| `current/dev/hardwood/benchmarks/compat/<concern>/` | the adapter for the current API; the pom compiles it by default, so `./mvnw compile` and an IDE build against Hardwood main |
| `<concern>/<n>-<label>/` | an adapter for an older API, the same classes in the same package; tried newest first, in name order |
| `<concern>/probe/` | for a feature no benchmark reaches through an adapter: a class that compiles only against a version that has it |
| `<concern>/since` | the first Hardwood release with the feature, for a concern older versions may lack |

| Concern | Adapter or probe | `since` |
| --- | --- | --- |
| `validity` | `LeafValidity`: `dev.hardwood.Validity`, or `dev.hardwood.reader.Validity` (1.0) | — |
| `binary` | `BinaryWindows`: `BinaryValues`, the buffer with starts and ends (Beta2, Beta3), or with offsets (1.0, Beta1) | — |
| `writer` | `Writers`: `compression` / `primitive` / a writer on a context, or `codec` / `addColumn` (Beta1 to Beta3) | 1.1.0.Beta1 |
| `dictionary-ids` | probe: `ColumnReader.getDictionaryIds()` | 1.1.0.Beta2 |
| `fixed-list-fast-path` | probe: `ReaderConfig` | 1.1.0.Beta1 |
| `s3-output-file` | probe: `S3Source.outputFile` | 1.1.0.CR1 |
| `alp` | probe: `ColumnEncoding.ALP` | 1.1.0.CR1 |
| `filter-unprojected` | none: the row reader filtering on a column outside its projection, behaviour no compile can detect | 1.1.0.Beta2 |

## Selection

`bench_build` resolves the classpath for the requested version, then for each concern
compiles its candidates with `javac` in order (the current adapter, the numbered ones, the
probe) and takes the first that compiles. The chosen adapters go into the version's build
directory and replace `current` as a source root. When no candidate compiles:

- the version predates the concern's `since`: the feature is unsupported on it;
- the concern has no candidate at all, for behaviour rather than API: its `since` alone decides;
- otherwise the build stops: the version has an API no adapter covers.

A -SNAPSHOT of X.Y.Z counts as newer than every pre-release of X.Y.Z, so it is expected
to have each feature whose `since` is one of them.

A package names the features it cannot run without in its `REQUIRES` file
(`write/REQUIRES`: `writer`); a script of that package, run against a version that predates
one, prints `Not supported by hardwood-core …` and exits 0 without running. A benchmark that runs without some of its contenders or
parameter values asks `Features.supported(...)` (the unsupported features reach the JVM in
`-Dbench.unsupported`) and reports what it leaves out with `Features.reportUnsupported(...)`;
`FloatEncodingBenchmark` drops `ALP` that way. `run-regression.sh` lists both under "Not
run", and treats a script that does not build as a defect.

## When the API changes

- **A rename or a changed signature in Hardwood main:** move the adapter in `current` to a
  new numbered directory of its concern, ahead of the existing ones, and write the new one
  in `current` against the new API.
- **A benchmark needs API that differs between versions:** add a concern with an adapter
  for each form, and call the adapter. Adapters on a timed path are plain delegating
  methods, which the JIT inlines.
- **A benchmark needs a feature older versions lack:** add a concern with a probe (or an
  adapter) and a `since`, and name it in the package's `REQUIRES`, or check it with
  `Features.supported` where only some contenders or values need it.

`run-regression.sh --smoke` checks the result: one round, one 1 s iteration per contender,
and per version which contenders ran and what was not run.
