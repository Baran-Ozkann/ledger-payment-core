-- The claim commits in the same transaction as the ledger write, so a row that another request can
-- see is always a completed one: an attempt that fails takes its own claim down with it. IN_PROGRESS
-- was therefore a state nobody could observe, and the 409 it fed was unreachable.
--
-- Keeping the claim inside the ledger transaction is the deliberate trade, and the reason this
-- column can go rather than the reason it should stay. The argument is written up in docs/future.md.

ALTER TABLE idempotency_keys DROP CONSTRAINT valid_status;
ALTER TABLE idempotency_keys DROP COLUMN status;
