# Load harness

Everything phase 5 measured, and how to measure it again. The findings are in
[RESULTS.md](RESULTS.md); this file is the operating manual.

## What is here

| Path | What it is |
|---|---|
| `k6/ledger.js` | Shared scenario code: the ramp, the per-step metrics, one transfer |
| `k6/scenario-u.js` | Uniform - both ends drawn at random from ten thousand accounts |
| `k6/scenario-h.js` | Hot account - every transfer credits the same REVENUE account |
| `seed.py` | Creates and funds the accounts, through the API |
| `run.py` | One measured run: reseed, start, confirm the database, ramp, collect |
| `measure.sh` | The four runs RESULTS.md reports, in order |
| `charts.py` | `results/*.json` to `charts/*.svg` |
| `results/` | Raw per-run output; the numbers in RESULTS.md come from here |
| `charts/` | The generated SVGs |

Python is the standard library only, and k6 is the only thing to install.

## The one thing to check before believing a number

A native PostgreSQL on the development machine listening on **5432** will answer a
misdirected run without complaining, and the resulting numbers look entirely plausible.
So the compose database is published on **5433** here, and `run.py` does not take the
configuration's word for it: it names every connection the application opens and then
asks the container how many connections by that name it is serving. A run whose
connections are not visible from inside the container is abandoned rather than reported.

The proof travels with the result. Every `results/*.json` carries a `connection` block
with the published host port, the server version and the cluster's system identifier.

## Running it

```bash
# 1. the stack, named service by service. A plain `docker compose up` would also start the
#    ledger service and its demo seeder, and run.py starts an application of its own on the
#    same port. PostgreSQL is published on 5433 either way.
docker compose up -d postgres kafka tempo prometheus grafana

# 2. the application under test, packaged - not mvn spring-boot:run, which would leave
#    Maven competing for the same cores as the thing being measured
./mvnw package -DskipTests

# 3. all four runs, about an hour
PG_PORT=5433 bash load/measure.sh

# 4. the charts
python load/charts.py
```

A single run, if that is all that is wanted:

```bash
python load/run.py --name u-read-committed --scenario u --pg-port 5433 --reseed
```

`run.py` starts and stops the application itself. Nothing should be listening on 8080
when it begins, which is why step 1 names its services rather than starting everything.
Prometheus carries a scrape target for each of the two places the application can run; the
one that is not running shows as down.

The application answers the API on **127.0.0.1:8080** and actuator on **8081**: the ledger
has no authentication and moves money, so it is not on the network, while the two read-only
actuator endpoints are, because Prometheus scrapes them from inside a container. It connects
as `ledger_app`, which cannot UPDATE or DELETE a ledger entry; migrations run as `ledger`.

## The ramp

One minute of warmup at 10 VUs, then 10, 50, 100, 200 and 400 VUs for two minutes each.
Warmup requests are sent and their timings discarded: they exist to get the transfer path
compiled, and letting them into a percentile would report the interpreter's speed as the
ledger's.

Each step keeps its own trend and counters, because a percentile taken across 10 and 400
VUs at once describes neither. `WARMUP_SECONDS` and `STEP_SECONDS` can shorten the ramp
for checking that the harness works; every run in RESULTS.md used the defaults.

## Reading a result file

```
results/<name>.json
  connection      which database this run actually used
  k6              per-step counters and duration trends
  postgres        pg_stat_activity and pg_stat_database, sampled every two seconds
  prometheus      pool, outbox, CPU and counter series over the run window
  scrapes         how many of those scrapes Prometheus actually got an answer to
  tablesBefore/After   row counts and on-disk size either side of the ramp
```

`<name>.app.log`, `<name>.seed.log` and `<name>.samples.csv` are the unparsed sources
behind it and are not committed.
