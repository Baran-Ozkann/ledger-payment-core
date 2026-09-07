// Everything the two scenarios agree on: where the ledger is, how a request is addressed, and how
// a sample is filed under the ramp step it belongs to. Scenario U and scenario H then differ in a
// single expression each, and keeping that the only difference is what makes them comparable.
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.LEDGER_URL || 'http://127.0.0.1:8080';
const ACCOUNTS_FILE = __ENV.ACCOUNTS_FILE || '../accounts.json';

// Stamped into every idempotency key. Nothing empties the database between runs, so without it a
// second run would replay the first one's keys and measure the stored-response path instead of
// the ledger. It scopes the client id too, so the keys of two runs cannot even collide.
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;

// The phase's figures are the defaults. They are overridable only so the harness can be proved
// to work end to end in a minute instead of eleven; every measured run in RESULTS.md used these.
const WARMUP_SECONDS = Number(__ENV.WARMUP_SECONDS || 60);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 120);

// The ramp the phase asks for. Each step is held flat rather than ramped through, so every sample
// inside it was taken at one concurrency level and its percentiles describe that level.
export const STEPS = [10, 50, 100, 200, 400];

// A SharedArray is parsed once for the whole process. Ten thousand ids copied into each of 400 VUs
// would cost more memory than the system under test is allowed to have.
const pool = new SharedArray('accounts', () => JSON.parse(open(ACCOUNTS_FILE)).accounts);
const fixtures = new SharedArray('fixtures', () => {
    const seed = JSON.parse(open(ACCOUNTS_FILE));
    return [{ revenue: seed.revenue }];
});

/** The one REVENUE account every scenario-H transfer credits. */
export const HOT_ACCOUNT = fixtures[0].revenue;

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 0,
            gracefulRampDown: '10s',
            stages: [
                { duration: `${WARMUP_SECONDS}s`, target: STEPS[0] },
                ...STEPS.flatMap((vus) => [
                    { duration: '0s', target: vus },
                    { duration: `${STEP_SECONDS}s`, target: vus },
                ]),
            ],
        },
    },
    summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max', 'count'],
    // Deliberately empty. A threshold that aborted the run would end it at the point the ramp was
    // built to reach: saturation is the measurement here, not the failure.
    thresholds: {},
};

// One trend and one set of counters per step. k6 summarises a run as a whole, and a percentile
// taken across 10 and 400 VUs together describes neither, so the step has to be part of the metric.
//
// Dropped is counted apart from rejected because they are different failures. A rejected request
// reached the server and came back with a status; a dropped one never got a connection, and k6
// reports it as status 0 with a duration of zero. Folding those zeros into the trend is how a
// saturated step ends up claiming a median of 0 ms, so they are kept out of it entirely.
const duration = {};
const requests = {};
const accepted = {};
const rejected = {};
const dropped = {};
for (const vus of STEPS) {
    duration[vus] = new Trend(`step_${vus}vu_duration`, true);
    requests[vus] = new Counter(`step_${vus}vu_requests`);
    accepted[vus] = new Counter(`step_${vus}vu_created`);
    rejected[vus] = new Counter(`step_${vus}vu_rejected`);
    dropped[vus] = new Counter(`step_${vus}vu_dropped`);
}

export function startClock() {
    return { startedAt: Date.now() };
}

export function randomAccount() {
    return pool[Math.floor(Math.random() * pool.length)];
}

/** Which flat step the clock is in, or null while the warmup is still running. */
function stepOf(startedAt) {
    const elapsed = (Date.now() - startedAt) / 1000;
    if (elapsed < WARMUP_SECONDS) {
        return null;
    }
    const index = Math.floor((elapsed - WARMUP_SECONDS) / STEP_SECONDS);
    return index >= 0 && index < STEPS.length ? STEPS[index] : null;
}

/**
 * One transfer, recorded against the step it started in. Warmup requests are sent and then thrown
 * away: compiling the transfer path is the only reason they exist, and letting their timings into
 * a percentile would mean reporting the interpreter's speed as the ledger's.
 */
export function transfer(clock, fromAccount, toAccount, amount) {
    const body = JSON.stringify({
        from_account: fromAccount,
        to_account: toAccount,
        amount,
        description: 'load',
    });
    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-Client-Id': `load-${RUN_ID}`,
            'Idempotency-Key': `${RUN_ID}-${__VU}-${__ITER}`,
        },
    };

    const response = http.post(`${BASE_URL}/v1/transfers`, body, params);

    const step = stepOf(clock.startedAt);
    if (step !== null) {
        requests[step].add(1);
        if (response.status === 0) {
            dropped[step].add(1);
        } else {
            duration[step].add(response.timings.duration);
            (response.status === 201 ? accepted[step] : rejected[step]).add(1);
        }
    }
    return response;
}

/** k6 writes whatever this returns, which is how a run leaves a machine-readable result behind. */
export function summaryTo(path, scenario) {
    return (data) => ({
        [path]: JSON.stringify({
            scenario,
            runId: RUN_ID,
            steps: STEPS,
            warmupSeconds: WARMUP_SECONDS,
            stepSeconds: STEP_SECONDS,
            metrics: data.metrics,
        }, null, 2),
        stdout: `${scenario}: ${data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0} requests\n`,
    });
}
