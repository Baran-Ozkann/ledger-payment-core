"""Runs one measured k6 ramp against a freshly started ledger and collects everything around it.

The order matters and is the reason this is a script rather than a list of commands in a README.
Before anything is measured the database the application actually opened connections to is
identified from that database's own side, because a native PostgreSQL listening on 5432 will
happily answer a misdirected run and produce numbers that look plausible and mean nothing.

Leaves load/results/<name>.json behind: the k6 per-step summary, samples taken from
pg_stat_activity and pg_stat_database while the ramp ran, and the matching Prometheus series.
"""

import argparse
import json
import os
import re
import pathlib
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).parent
PROJECT = HERE.parent
RESULTS = HERE / "results"

APP_PORT = 8080
PROMETHEUS = "http://localhost:9090"
SAMPLE_SECONDS = 2
SAMPLER_NAME = "load-sampler"
STARTUP_TIMEOUT_SECONDS = 120

PROMETHEUS_SERIES = [
    "hikaricp_connections_active",
    "hikaricp_connections_idle",
    "hikaricp_connections_pending",
    "hikaricp_connections_max",
    "hikaricp_connections_acquire_seconds_max",
    "ledger_outbox_pending",
    "ledger_outbox_lag_seconds",
    "ledger_deadlock_retry_total",
    "ledger_serialization_retry_total",
    "process_cpu_usage",
    "system_cpu_usage",
    "jvm_threads_live_threads",
    'sum by (result) (ledger_transfer_total{operation="transfer"})',
]

# One statement per fact, each prefixed so the reader can split the stream without a parser. The
# sampler's own backend is excluded: it is always active and always the one running this query.
SAMPLE_SQL = """
SELECT 'A,'||(extract(epoch from clock_timestamp())*1000)::bigint||','||
       count(*) FILTER (WHERE state = 'active')||','||
       count(*) FILTER (WHERE state = 'idle in transaction')||','||
       count(*) FILTER (WHERE wait_event_type = 'Lock')||','||
       count(*) FILTER (WHERE wait_event_type IS NOT NULL AND wait_event_type <> 'Lock')||','||
       count(*)
  FROM pg_stat_activity
 WHERE datname = 'ledger' AND backend_type = 'client backend' AND pid <> pg_backend_pid();
SELECT 'W,'||(extract(epoch from clock_timestamp())*1000)::bigint||','||
       coalesce(wait_event_type, 'Running')||','||coalesce(wait_event, '-')||','||count(*)
  FROM pg_stat_activity
 WHERE datname = 'ledger' AND backend_type = 'client backend'
   AND state = 'active' AND pid <> pg_backend_pid()
 GROUP BY wait_event_type, wait_event;
SELECT 'D,'||(extract(epoch from clock_timestamp())*1000)::bigint||','||
       deadlocks||','||xact_commit||','||xact_rollback||','||tup_updated||','||tup_fetched
  FROM pg_stat_database WHERE datname = 'ledger';
SELECT 'L,'||(extract(epoch from clock_timestamp())*1000)::bigint||','||
       locktype||','||granted||','||count(*)
  FROM pg_locks WHERE locktype IN ('tuple', 'transactionid', 'relation')
 GROUP BY locktype, granted;
SELECT pg_sleep(%d);
""" % SAMPLE_SECONDS


def psql(sql: str) -> str:
    """Runs SQL inside the compose container, which is the only PostgreSQL this script trusts."""
    answer = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres",
         "psql", "-U", "ledger", "-d", "ledger", "-t", "-A", "-F", ",", "-v", "ON_ERROR_STOP=1",
         "-c", sql],
        cwd=PROJECT, capture_output=True, text=True)
    if answer.returncode != 0:
        raise SystemExit(f"psql failed on {sql!r}: {answer.stderr.strip()}")
    return answer.stdout.strip()


def start_application(jar: pathlib.Path, jdbc_url: str, profiles: list[str], log: pathlib.Path):
    command = [
        "java",
        "-jar", str(jar),
        f"--spring.datasource.url={jdbc_url}",
        "--spring.datasource.username=ledger",
        "--spring.datasource.password=ledger",
    ]
    if profiles:
        command.append("--spring.profiles.active=" + ",".join(profiles))

    handle = log.open("w", encoding="utf-8")
    return subprocess.Popen(command, cwd=PROJECT, stdout=handle, stderr=subprocess.STDOUT), handle


def await_health() -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"http://localhost:{APP_PORT}/actuator/health", timeout=5) as answer:
                if json.loads(answer.read())["status"] == "UP":
                    return
        except (urllib.error.URLError, OSError, KeyError, ValueError):
            time.sleep(1)
    raise SystemExit(f"the application did not become healthy within {STARTUP_TIMEOUT_SECONDS}s")


def confirm_database(application_name: str) -> dict:
    """Asks the container which connections it is serving, rather than asking the application.

    A configuration value only says where the application meant to connect. This says where it did,
    from the other end, and the run is abandoned if the answer is not the compose PostgreSQL.
    """
    backends = psql(
        "SELECT count(*), coalesce(min(inet_server_port()::text), '?') FROM pg_stat_activity "
        f"WHERE application_name = '{application_name}'")
    count, container_port = backends.split(",")
    published = subprocess.run(
        ["docker", "compose", "port", "postgres", "5432"],
        cwd=PROJECT, check=True, capture_output=True, text=True).stdout.strip()
    version = psql("SELECT version()")
    identifier = psql("SELECT system_identifier FROM pg_control_system()")

    if int(count) == 0:
        raise SystemExit(
            f"no connection named {application_name} is open on the compose PostgreSQL; the "
            f"application is talking to some other server")

    return {
        "applicationName": application_name,
        "backendsSeenByContainer": int(count),
        "portInsideContainer": container_port,
        "publishedOnHost": published,
        "serverVersion": version,
        "systemIdentifier": identifier,
    }


def start_sampler(iterations: int, out: pathlib.Path, script: pathlib.Path):
    """Feeds psql from a file rather than through a pipe this process writes to.

    The statements are handed over faster than psql consumes them - it sleeps two seconds between
    rounds - so a pipe fed from here would fill, block the writer, and hang the run before k6 was
    ever started.
    """
    script.write_text(SAMPLE_SQL * iterations, encoding="utf-8")
    handle = out.open("w", encoding="utf-8")
    source = script.open("r", encoding="utf-8")
    process = subprocess.Popen(
        ["docker", "compose", "exec", "-T", "-e", f"PGAPPNAME={SAMPLER_NAME}", "postgres",
         "psql", "-U", "ledger", "-d", "ledger", "-t", "-A", "-F", ","],
        cwd=PROJECT, stdin=source, stdout=handle, stderr=subprocess.DEVNULL, text=True)
    source.close()
    return process, handle


def stop_sampler() -> int:
    """Ends the sampler inside the database, which killing the exec client does not do.

    docker compose exec starts a process in the container and the local handle is only a pipe to
    it. Killing that handle leaves psql running its remaining rounds, and a sampler outliving its
    run turns up as unexplained backends in the next one - so it is terminated by name, from the
    server side, which is the only end that can actually stop it.
    """
    terminated = psql("SELECT count(*) FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                      f" WHERE application_name = '{SAMPLER_NAME}') AS ended")
    return int(terminated or 0)


def empty_the_ledger() -> None:
    """Puts the database back to empty so every measured run starts from the same depth.

    Runs with nothing connected. TRUNCATE takes ACCESS EXCLUSIVE on every table it names, and the
    relay holds a transaction on outbox_events five times a second, so issuing this against a
    running instance is a deadlock waiting to be reported as a mysterious exit status.

    TRUNCATE rather than DELETE, the same way the concurrency tests empty the ledger: I5's trigger
    refuses a DELETE, and discarding a fixture is not a correction to history.
    """
    psql("TRUNCATE account_activity, consumed_events, outbox_events, idempotency_keys, "
         "ledger_entries, ledger_transactions, accounts RESTART IDENTITY")


def reseed(jar: pathlib.Path, jdbc_url: str, log: pathlib.Path, accounts: int) -> None:
    """Rebuilds the fixture on an instance running the default profile, never the measured one.

    Funding ten thousand accounts debits one EQUITY row ten thousand times, which is the hardest
    contention this system ever sees. Under the serializable profile most of it would exhaust its
    retries and the ramp would then run against half-funded accounts, so the seeding instance is
    always the default one and is shut down before the measured instance starts.
    """
    empty_the_ledger()
    application, handle = start_application(jar, jdbc_url, [], log)
    try:
        await_health()
        seed = subprocess.run(
            [sys.executable, str(HERE / "seed.py"), "--url", f"http://localhost:{APP_PORT}",
             "--accounts", str(accounts)],
            cwd=PROJECT, check=False, capture_output=True, text=True)
        print(seed.stdout.strip(), flush=True)
        if seed.returncode != 0:
            raise SystemExit(f"seeding failed: {seed.stderr.strip()}")
    finally:
        stop_application(application, handle)


def stop_application(application, handle) -> None:
    application.send_signal(signal.SIGTERM)
    try:
        application.wait(timeout=60)
    except subprocess.TimeoutExpired:
        application.kill()
    handle.close()
    # The port is not free the instant the process is: the next instance binds 8080 too, and a
    # start that races the previous socket fails in a way that looks like a configuration error.
    time.sleep(5)


def run_k6(k6: str, script: pathlib.Path, run_id: str, summary: pathlib.Path) -> int:
    environment = dict(os.environ)
    environment.update({
        "RUN_ID": run_id,
        "SUMMARY_FILE": str(summary),
        "ACCOUNTS_FILE": (HERE / "accounts.json").as_posix(),
        "LEDGER_URL": f"http://localhost:{APP_PORT}",
        "K6_NO_USAGE_REPORT": "true",
    })
    return subprocess.run([k6, "run", str(script)], cwd=HERE / "k6", env=environment).returncode


def prometheus_range(query: str, start: float, end: float) -> dict:
    parameters = urllib.parse.urlencode(
        {"query": query, "start": f"{start:.0f}", "end": f"{end:.0f}", "step": "5s"})
    with urllib.request.urlopen(f"{PROMETHEUS}/api/v1/query_range?{parameters}", timeout=60) as answer:
        return json.loads(answer.read())["data"]["result"]


def parse_samples(raw: pathlib.Path) -> dict:
    activity, waits, database, locks = [], [], [], []
    for line in raw.read_text(encoding="utf-8").splitlines():
        fields = line.strip().split(",")
        if fields[0] == "A" and len(fields) == 7:
            activity.append({"at": int(fields[1]), "active": int(fields[2]),
                             "idleInTransaction": int(fields[3]), "waitingOnLock": int(fields[4]),
                             "waitingElsewhere": int(fields[5]), "backends": int(fields[6])})
        elif fields[0] == "W" and len(fields) == 5:
            waits.append({"at": int(fields[1]), "type": fields[2],
                          "event": fields[3], "backends": int(fields[4])})
        elif fields[0] == "D" and len(fields) == 7:
            database.append({"at": int(fields[1]), "deadlocks": int(fields[2]),
                             "commits": int(fields[3]), "rollbacks": int(fields[4]),
                             "tuplesUpdated": int(fields[5]), "tuplesFetched": int(fields[6])})
        elif fields[0] == "L" and len(fields) == 5:
            locks.append({"at": int(fields[1]), "locktype": fields[2],
                          "granted": fields[3] == "t", "count": int(fields[4])})
    return {"activity": activity, "waitEvents": waits, "database": database, "locks": locks}


def table_sizes() -> dict:
    rows = psql("SELECT relname, n_live_tup, pg_total_relation_size(relid) FROM pg_stat_user_tables "
                "ORDER BY relname")
    sizes = {}
    for line in rows.splitlines():
        name, live, bytes_on_disk = line.split(",")
        sizes[name] = {"liveTuples": int(live), "bytes": int(bytes_on_disk)}
    return sizes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True, help="result file name, e.g. u-read-committed")
    parser.add_argument("--scenario", required=True, choices=["u", "h"])
    parser.add_argument("--profiles", default="", help="comma separated Spring profiles")
    parser.add_argument("--pg-port", type=int, default=5433,
                        help="host port the COMPOSE PostgreSQL is published on")
    parser.add_argument("--k6", default=shutil.which("k6") or "k6")
    parser.add_argument("--reseed", action="store_true",
                        help="empty and rebuild the accounts before measuring")
    parser.add_argument("--accounts", type=int, default=10_000)
    parser.add_argument("--jar", default=str(PROJECT / "target" / "ledger-0.0.1-SNAPSHOT.jar"))
    arguments = parser.parse_args()

    # The name reaches psql inside a quoted SQL literal, so it is checked rather than escaped.
    # Nobody hostile is passing this argument, but a name with an apostrophe in it would produce a
    # confusing syntax error at best, and this script is the template anyone copies.
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", arguments.name):
        raise SystemExit("--name must be 1-64 characters of letters, digits, dot, dash or underscore")

    RESULTS.mkdir(exist_ok=True)
    run_id = f"{arguments.name}-{int(time.time())}"
    application_name = f"ledger-{run_id}"
    jdbc_url = (f"jdbc:postgresql://localhost:{arguments.pg_port}/ledger"
                f"?ApplicationName={application_name}")
    profiles = [profile for profile in arguments.profiles.split(",") if profile]

    log = RESULTS / f"{arguments.name}.app.log"
    raw_samples = RESULTS / f"{arguments.name}.samples.csv"
    summary = RESULTS / f"{arguments.name}.k6.json"

    stragglers = stop_sampler()
    if stragglers:
        print(f"[{arguments.name}] ended {stragglers} sampler(s) left over from an earlier run",
              flush=True)

    if arguments.reseed:
        print(f"[{arguments.name}] rebuilding the fixture", flush=True)
        reseed(pathlib.Path(arguments.jar), jdbc_url, RESULTS / f"{arguments.name}.seed.log",
               arguments.accounts)

    print(f"[{arguments.name}] starting the application on {jdbc_url}", flush=True)
    application, log_handle = start_application(pathlib.Path(arguments.jar), jdbc_url, profiles, log)
    sampler = sampler_handle = None
    try:
        await_health()
        connection = confirm_database(application_name)
        print(f"[{arguments.name}] connected to compose PostgreSQL "
              f"published on {connection['publishedOnHost']}, "
              f"system identifier {connection['systemIdentifier']}", flush=True)

        before = table_sizes()
        started = time.time()
        # Enough rounds to outlast the longest ramp; it is terminated by name when k6 finishes,
        # so the count only has to be an upper bound rather than a matched duration.
        sampler, sampler_handle = start_sampler(
            500, raw_samples, RESULTS / f"{arguments.name}.sampler.sql")
        code = run_k6(arguments.k6, HERE / "k6" / f"scenario-{arguments.scenario}.js", run_id, summary)
        ended = time.time()
    finally:
        stop_sampler()
        if sampler is not None:
            sampler.kill()
            sampler.wait()
            sampler_handle.close()
        stop_application(application, log_handle)

    if code != 0:
        print(f"k6 exited {code}; the result is still written for inspection", file=sys.stderr)

    # Prometheus is asked a minute either side, so a scrape that landed just outside the ramp is
    # still there to show what the system looked like before it and after it.
    series = {query: prometheus_range(query, started - 60, ended + 60) for query in PROMETHEUS_SERIES}

    result = {
        "name": arguments.name,
        "scenario": arguments.scenario.upper(),
        "profiles": profiles,
        "runId": run_id,
        "startedAt": started,
        "endedAt": ended,
        "k6ExitCode": code,
        "connection": connection,
        "k6": json.loads(summary.read_text(encoding="utf-8")) if summary.exists() else None,
        "postgres": parse_samples(raw_samples),
        "prometheus": series,
        "tablesBefore": before,
        "tablesAfter": table_sizes(),
    }
    out = RESULTS / f"{arguments.name}.json"
    out.write_text(json.dumps(result, indent=1), encoding="utf-8")
    print(f"[{arguments.name}] wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
