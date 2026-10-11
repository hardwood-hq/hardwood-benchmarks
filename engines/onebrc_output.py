"""The 1BRC output every contender of run-1brc-engines.sh prints, from per-station aggregates
in tenths of a degree: `{name=min/mean/max, ...}`, stations in the unsigned order of their
UTF-8 bytes, each value with one fractional digit and the mean rounded half up."""


def tenths(value):
    return ("-" if value < 0 else "") + f"{abs(value) // 10}.{abs(value) % 10}"


def format_stations(rows):
    """`rows`: (station, min, max, sum, count) tuples, values in tenths."""
    rows = sorted(rows, key=lambda r: r[0].encode("utf-8"))
    return "{" + ", ".join(
        f"{station}={tenths(mn)}/{tenths((2 * total + count) // (2 * count))}/{tenths(mx)}"
        for station, mn, mx, total, count in rows) + "}"
