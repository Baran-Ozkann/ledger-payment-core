// Scenario U - uniform. Both ends of every transfer are drawn at random from ten thousand
// accounts, so two concurrent transfers share a row about one time in five thousand. This is the
// baseline the hot-account scenario is measured against: the same code, the same request, and
// contention as close to absent as a real workload gets.
import { transfer, randomAccount, startClock, summaryTo } from './ledger.js';

export { options } from './ledger.js';

const AMOUNT = 1_000;

export function setup() {
    return startClock();
}

export default function (clock) {
    const from = randomAccount();
    let to = randomAccount();
    // V3 refuses a self-transfer with a 422, and a scenario that spent a slice of its requests
    // being rejected would be measuring the validation path, not the ledger.
    while (to === from) {
        to = randomAccount();
    }
    transfer(clock, from, to, AMOUNT);
}

export const handleSummary = summaryTo(__ENV.SUMMARY_FILE, 'U');
