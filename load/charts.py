"""Turns load/results/*.json into the SVG charts RESULTS.md and the phase 6 README embed.

SVG and no plotting library on purpose: a chart in this repository is reviewed in a diff like
everything else, and a PNG is an opaque blob that has to be regenerated to be trusted. These files
are text, they are produced from the result JSON by this script alone, and re-running it against
the same results produces the same bytes.

Every number a chart shows is also written out as a table in RESULTS.md, so nothing here is the
only way to reach a value.
"""

import json
import math
import pathlib

HERE = pathlib.Path(__file__).parent
RESULTS = HERE / "results"
CHARTS = HERE / "charts"

# The categorical slots, in the fixed order of the reference palette. Assigned by entity - scenario
# U is always blue and scenario H always orange, in every chart - never by rank within a chart.
BLUE, ORANGE, AQUA, YELLOW = "#2a78d6", "#eb6834", "#1baf7a", "#eda100"
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SOFT = "#52514e"
GRID = "#e6e5e1"

FONT = ("system-ui, -apple-system, 'Segoe UI', Roboto, "
        "'Helvetica Neue', Arial, sans-serif")


def escape(text: str) -> str:
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, body, size=12, fill=INK_SOFT, anchor="start", weight="400"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" '
            f'fill="{fill}" text-anchor="{anchor}" font-weight="{weight}">{escape(body)}</text>')


def document(width, height, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" role="img" aria-label="{escape(title)}">'
            f'<title>{escape(title)}</title>'
            f'<rect width="{width}" height="{height}" fill="{SURFACE}"/>{body}</svg>')


def nice_ceiling(value: float) -> float:
    """Rounds an axis top up to 1, 2 or 5 times a power of ten, so ticks land on read numbers."""
    if value <= 0:
        return 1.0
    magnitude = 10 ** math.floor(math.log10(value))
    for step in (1, 2, 2.5, 5, 10):
        if value <= step * magnitude:
            return step * magnitude
    return 10 * magnitude


def format_count(value: float) -> str:
    """Backends and connections are whole things; half a connection is not a reading."""
    return f"{value:,.0f}"


def format_number(value: float) -> str:
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 10:
        return f"{value:.0f}"
    if value >= 1:
        return f"{value:.1f}"
    return f"{value:.2f}"


class Panel:
    """One plot area: a linear or log y-axis, a categorical x-axis, and recessive gridlines."""

    def __init__(self, left, top, width, height, y_max, labels, y_min=0.0, log=False, ticks=5):
        self.left, self.top, self.width, self.height = left, top, width, height
        self.labels = labels
        self.log = log
        self.y_min = max(y_min, 1e-3) if log else y_min
        self.y_max = y_max
        self.ticks = ticks

    def y(self, value):
        if self.log:
            value = max(value, self.y_min)
            span = math.log10(self.y_max) - math.log10(self.y_min)
            fraction = (math.log10(value) - math.log10(self.y_min)) / span
        else:
            fraction = (value - self.y_min) / (self.y_max - self.y_min)
        return self.top + self.height - fraction * self.height

    def band(self, index):
        step = self.width / len(self.labels)
        return self.left + index * step, step

    def centre(self, index):
        start, step = self.band(index)
        return start + step / 2

    def axes(self, y_label, y_format=format_number):
        parts = []
        if self.log:
            values, decade = [], 10 ** math.floor(math.log10(self.y_min))
            while decade <= self.y_max:
                for multiple in (1, 3):
                    tick = decade * multiple
                    if self.y_min <= tick <= self.y_max:
                        values.append(tick)
                decade *= 10
        else:
            values = [self.y_min + (self.y_max - self.y_min) * n / self.ticks
                      for n in range(self.ticks + 1)]
        for value in values:
            y = self.y(value)
            parts.append(f'<line x1="{self.left:.1f}" y1="{y:.1f}" '
                         f'x2="{self.left + self.width:.1f}" y2="{y:.1f}" '
                         f'stroke="{GRID}" stroke-width="1"/>')
            parts.append(text(self.left - 8, y + 4, y_format(value), 11, INK_SOFT, "end"))
        for index, label in enumerate(self.labels):
            parts.append(text(self.centre(index), self.top + self.height + 18, label, 11,
                              INK_SOFT, "middle"))
        parts.append(text(self.left - 8, self.top - 12, y_label, 11, INK_SOFT, "end"))
        return "".join(parts)

    def columns(self, series, colours, names):
        """Grouped columns, capped at 24px, 4px rounded cap, 2px of surface between neighbours.

        Only the peak of each series and its last step carry a printed value. A number on every
        column is unreadable and goes unread; the axis carries the rest, and RESULTS.md carries
        all of it exactly.
        """
        labelled = {(position, values.index(max(values))) for position, values in enumerate(series)}
        labelled |= {(position, len(self.labels) - 1) for position in range(len(series))}
        parts = []
        for index in range(len(self.labels)):
            start, step = self.band(index)
            slot = (step - 16) / len(series)
            for position, values in enumerate(series):
                value = values[index]
                thickness = min(24.0, slot - 2)
                x = start + 8 + position * slot + (slot - thickness) / 2
                top = self.y(value)
                height = max(self.top + self.height - top, 0.5)
                radius = min(4.0, height)
                parts.append(
                    f'<path d="M{x:.1f} {top + height:.1f} V{top + radius:.1f} '
                    f'q0 {-radius:.1f} {radius:.1f} {-radius:.1f} '
                    f'h{thickness - 2 * radius:.1f} q{radius:.1f} 0 {radius:.1f} {radius:.1f} '
                    f'V{top + height:.1f} Z" fill="{colours[position]}">'
                    f'<title>{escape(names[position])} at {escape(self.labels[index])}: '
                    f'{escape(format_number(value))}</title></path>')
                if (position, index) in labelled:
                    parts.append(text(x + thickness / 2, top - 6, format_number(value), 10,
                                      INK_SOFT, "middle"))
        return "".join(parts)

    def line(self, values, colour, name, dash=None):
        points = [(self.centre(index), self.y(value)) for index, value in enumerate(values)]
        path = " ".join(f"{'M' if n == 0 else 'L'}{x:.1f} {y:.1f}" for n, (x, y) in enumerate(points))
        stroke = f' stroke-dasharray="{dash}"' if dash else ""
        parts = [f'<path d="{path}" fill="none" stroke="{colour}" stroke-width="2" '
                 f'stroke-linejoin="round" stroke-linecap="round"{stroke}/>']
        for (x, y), value in zip(points, values):
            parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4.5" fill="{colour}" '
                         f'stroke="{SURFACE}" stroke-width="2">'
                         f'<title>{escape(name)}: {escape(format_number(value))}</title></circle>')
        return "".join(parts)

    def series_over_time(self, points, colour, name, start, end):
        """A time series drawn across the panel, x mapped from wall clock rather than from bands.

        Samples outside the window are dropped rather than clamped to the edge. Prometheus is asked
        a minute either side of the ramp, and clamping stacked all of that minute onto the first
        pixel, which drew a vertical stroke that looked like a spike and was an artefact.
        """
        inside = [(at, value) for at, value in points if start <= at <= end]
        if not inside:
            return ""
        span = max(end - start, 1)
        path = []
        for index, (at, value) in enumerate(inside):
            x = self.left + self.width * (at - start) / span
            path.append(f"{'M' if index == 0 else 'L'}{x:.1f} {self.y(value):.1f}")
        return (f'<path d="{" ".join(path)}" fill="none" stroke="{colour}" stroke-width="2" '
                f'stroke-linejoin="round"><title>{escape(name)}</title></path>')


def legend(x, y, entries):
    parts = []
    for label, colour in entries:
        parts.append(f'<rect x="{x:.1f}" y="{y - 8:.1f}" width="10" height="10" rx="2" '
                     f'fill="{colour}"/>')
        parts.append(text(x + 16, y + 1, label, 11, INK_SOFT))
        x += 22 + 6.6 * len(label)
    return "".join(parts)


def load(name):
    path = RESULTS / f"{name}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def step_table(result):
    """Per-step figures, taken from the counters the scenario kept rather than from k6's totals."""
    k6 = result["k6"]
    seconds = k6["stepSeconds"]
    rows = []
    for vus in k6["steps"]:
        metrics = k6["metrics"]

        def count(suffix):
            metric = metrics.get(f"step_{vus}vu_{suffix}")
            return metric["values"]["count"] if metric else 0

        trend = metrics.get(f"step_{vus}vu_duration")
        values = trend["values"] if trend else {}
        rows.append({
            "vus": vus,
            "requests": count("requests"),
            "created": count("created"),
            "rejected": count("rejected"),
            "dropped": count("dropped"),
            "tps": count("created") / seconds,
            "p50": values.get("med", 0.0),
            "p95": values.get("p(95)", 0.0),
            "p99": values.get("p(99)", 0.0),
            "max": values.get("max", 0.0),
        })
    return rows


def series_of(result, query, transform=float):
    """One Prometheus series flattened to (epoch seconds, value), summed where labels split it."""
    raw = result["prometheus"].get(query) or []
    merged = {}
    for stream in raw:
        for at, value in stream["values"]:
            merged[float(at)] = merged.get(float(at), 0.0) + transform(value)
    return sorted(merged.items())


def chart_throughput(results):
    labels = [f"{row['vus']} VU" for row in step_table(results["u-read-committed"])]
    series = [[row["tps"] for row in step_table(results[name])]
              for name in ("u-read-committed", "h-read-committed")]
    top = nice_ceiling(max(max(values) for values in series))
    panel = Panel(64, 64, 760, 300, top, labels)
    body = (
        text(24, 30, "Transfers committed per second", 15, INK, weight="600")
        + text(24, 48, "READ COMMITTED with ordered locking, two minutes per step", 11, INK_SOFT)
        + panel.axes("tx/s")
        + panel.columns(series, [BLUE, ORANGE], ["Scenario U", "Scenario H"])
        + legend(64, 408, [("Scenario U - random pairs", BLUE),
                           ("Scenario H - one shared REVENUE account", ORANGE)]))
    return document(880, 430, body, "Committed transfers per second, scenario U against scenario H")


def chart_latency(results):
    names = [("u-read-committed", "Scenario U", BLUE), ("h-read-committed", "Scenario H", ORANGE)]
    rows = {name: step_table(results[name]) for name, _, _ in names}
    labels = [f"{row['vus']}" for row in rows["u-read-committed"]]
    highest = max(row["p99"] for table in rows.values() for row in table)
    lowest = min(row["p50"] for table in rows.values() for row in table)

    parts = [text(24, 30, "Request latency by ramp step", 15, INK, weight="600"),
             text(24, 48, "Log scale, shared between the two panels; VUs along the bottom",
                  11, INK_SOFT)]
    for index, (name, title, colour) in enumerate(names):
        panel = Panel(64 + index * 420, 78, 330, 280,
                      nice_ceiling(highest), labels, y_min=max(lowest / 2, 1.0), log=True)
        parts.append(text(64 + index * 420, 70, title, 12, INK, weight="600"))
        parts.append(panel.axes("ms" if index == 0 else ""))
        parts.append(panel.line([row["p50"] for row in rows[name]], colour, "p50"))
        parts.append(panel.line([row["p95"] for row in rows[name]], colour, "p95", dash="6 4"))
        parts.append(panel.line([row["p99"] for row in rows[name]], colour, "p99", dash="2 3"))
        # One label per panel, on the extreme. At 400 VUs scenario H's p50 and p99 are within
        # three percent of each other, so labelling both would put two strings on one pixel row;
        # nudging them apart would detach each from its line. The table below carries every value.
        last = rows[name][-1]
        parts.append(text(panel.left + panel.width, panel.y(last["p99"]) - 8,
                          f"p99 {format_number(last['p99'])} ms at 400 VU", 10, INK_SOFT, "end"))
    parts.append('<line x1="64" y1="404" x2="94" y2="404" stroke="' + INK_SOFT + '" stroke-width="2"/>')
    parts.append(text(100, 408, "p50", 11, INK_SOFT))
    parts.append('<line x1="140" y1="404" x2="170" y2="404" stroke="' + INK_SOFT
                 + '" stroke-width="2" stroke-dasharray="6 4"/>')
    parts.append(text(176, 408, "p95", 11, INK_SOFT))
    parts.append('<line x1="216" y1="404" x2="246" y2="404" stroke="' + INK_SOFT
                 + '" stroke-width="2" stroke-dasharray="2 3"/>')
    parts.append(text(252, 408, "p99", 11, INK_SOFT))
    parts.append(legend(320, 408, [("Scenario U", BLUE), ("Scenario H", ORANGE)]))
    return document(880, 430, "".join(parts), "Latency percentiles by ramp step")


def chart_isolation(results):
    pairs = [("Scenario U", "u-read-committed", "u-serializable", BLUE),
             ("Scenario H", "h-read-committed", "h-serializable", ORANGE)]
    tables = {name: step_table(result) for name, result in results.items()}
    labels = [f"{row['vus']}" for row in tables["u-read-committed"]]
    top = nice_ceiling(max(row["tps"] for table in tables.values() for row in table))

    parts = [text(24, 30, "What SERIALIZABLE with a retry loop costs", 15, INK, weight="600"),
             text(24, 48, "Committed transfers per second; same scale in both panels",
                  11, INK_SOFT)]
    for index, (title, ordered, serializable, colour) in enumerate(pairs):
        panel = Panel(64 + index * 420, 78, 330, 280, top, labels)
        parts.append(text(64 + index * 420, 70, title, 12, INK, weight="600"))
        parts.append(panel.axes("tx/s" if index == 0 else ""))
        parts.append(panel.columns(
            [[row["tps"] for row in tables[ordered]], [row["tps"] for row in tables[serializable]]],
            [colour, AQUA], ["ordered locking", "SERIALIZABLE"]))
    parts.append(legend(64, 408, [("READ COMMITTED, ordered locking", BLUE),
                                  ("SERIALIZABLE with retries", AQUA)]))
    parts.append(text(430, 408, "(scenario H keeps its own hue for the ordered-locking bars)",
                      10, INK_SOFT))
    return document(880, 430, "".join(parts), "READ COMMITTED against SERIALIZABLE, both scenarios")


def chart_saturation(result, title, subtitle):
    """Three stacked panels sharing one clock. Three scales, so three axes - never two on one."""
    start, end = result["startedAt"], result["endedAt"]
    pool_active = series_of(result, "hikaricp_connections_active")
    pool_pending = series_of(result, "hikaricp_connections_pending")
    pool_max = max([value for _, value in series_of(result, "hikaricp_connections_max")] or [10])
    waiting = [(row["at"] / 1000.0, row["waitingOnLock"]) for row in result["postgres"]["activity"]]
    lag = series_of(result, "ledger_outbox_lag_seconds")

    rows = [
        (f"Pool connections in use (max {format_count(pool_max)})",
         [(pool_active, BLUE, "active")], pool_max, format_count),
        ("Threads queued for a connection", [(pool_pending, ORANGE, "pending")],
         nice_ceiling(max([value for _, value in pool_pending] or [1])), format_count),
        ("Backends waiting on a row lock", [(waiting, AQUA, "waiting on Lock")],
         max(nice_ceiling(max([value for _, value in waiting] or [1])), 2.0), format_count),
        ("Outbox lag (seconds)", [(lag, YELLOW, "oldest unpublished row")],
         nice_ceiling(max([value for _, value in lag] or [1])), format_count),
    ]

    parts = [text(24, 30, title, 15, INK, weight="600"), text(24, 48, subtitle, 11, INK_SOFT)]
    warmup, step = result["k6"]["warmupSeconds"], result["k6"]["stepSeconds"]
    for index, (label, series, top, formatter) in enumerate(rows):
        panel = Panel(180, 78 + index * 122, 660, 88, top, [""], ticks=2)
        parts.append(text(172, 78 + index * 122 + 46, label, 11, INK_SOFT, "end"))
        parts.append(panel.axes("", formatter))
        for boundary, vus in enumerate(result["k6"]["steps"]):
            at = start + warmup + boundary * step
            x = panel.left + panel.width * (at - start) / max(end - start, 1)
            parts.append(f'<line x1="{x:.1f}" y1="{panel.top:.1f}" x2="{x:.1f}" '
                         f'y2="{panel.top + panel.height:.1f}" stroke="{GRID}" stroke-width="1"/>')
            if index == 0:
                parts.append(text(x + 4, panel.top - 6, f"{vus} VU", 10, INK_SOFT))
        for points, colour, name in series:
            parts.append(panel.series_over_time(points, colour, name, start, end))
    parts.append(text(180, 78 + len(rows) * 122 + 4, "time through the ramp", 11, INK_SOFT))
    return document(880, 78 + len(rows) * 122 + 24, "".join(parts), title)


def main() -> int:
    CHARTS.mkdir(exist_ok=True)
    names = ["u-read-committed", "h-read-committed", "u-serializable", "h-serializable"]
    results = {name: load(name) for name in names}
    missing = [name for name, result in results.items() if result is None]
    if missing:
        print(f"no result for {', '.join(missing)}; run load/measure.sh first")
        return 1

    written = {
        "tps-by-step.svg": chart_throughput(results),
        "latency-by-step.svg": chart_latency(results),
        "isolation-comparison.svg": chart_isolation(results),
        "saturation-u.svg": chart_saturation(
            results["u-read-committed"], "Where scenario U stops scaling",
            "READ COMMITTED with ordered locking, random account pairs"),
        "saturation-h.svg": chart_saturation(
            results["h-read-committed"], "Where scenario H stops scaling",
            "READ COMMITTED with ordered locking, every transfer credits one REVENUE account"),
    }
    for filename, content in written.items():
        (CHARTS / filename).write_text(content, encoding="utf-8")
        print(f"wrote {CHARTS / filename}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
