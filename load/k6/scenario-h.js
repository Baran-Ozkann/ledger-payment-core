// Scenario H - hot account. Every transfer credits the same REVENUE account, the way a fee would.
// One line differs from scenario U: the destination is fixed. That is deliberate, because it makes
// the difference between the two runs attributable to the shared row and to nothing else.
//
// Both transactions still take two account locks and write two entries and two outbox rows. What
// changes is that every transaction in the system now wants the same one of those rows.
import { transfer, randomAccount, startClock, summaryTo, HOT_ACCOUNT } from './ledger.js';

export { options } from './ledger.js';

const AMOUNT = 1_000;

export function setup() {
    return startClock();
}

export default function (clock) {
    transfer(clock, randomAccount(), HOT_ACCOUNT, AMOUNT);
}

export const handleSummary = summaryTo(__ENV.SUMMARY_FILE, 'H');
