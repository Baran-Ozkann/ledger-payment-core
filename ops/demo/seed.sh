#!/bin/sh
# Creates the two accounts the quick start transfers between, and funds one of them, so that the
# step after `docker compose up` really is a single request rather than four.
#
# Every call carries a fixed Idempotency-Key. Bringing the stack up again therefore replays these
# four requests instead of creating four more accounts, and the ids printed below stay the same for
# the life of the volume - which is the idempotency protocol doing its job, not a special case.
set -eu

API="${LEDGER_API:-http://ledger:8080}"
CLIENT="demo-seed"

post() {
    path="$1"
    key="$2"
    body="$3"
    curl -sS -X POST "$API$path" \
        -H 'Content-Type: application/json' \
        -H "X-Client-Id: $CLIENT" \
        -H "Idempotency-Key: $key" \
        -d "$body"
}

# The response's first "id" is the resource's public id; every other id in it is nested.
#
# The whitespace in the pattern is not decoration. A first execution answers with Jackson's output;
# a replay answers with the same document read back out of a jsonb column, which PostgreSQL returns
# with its keys reordered and a space after every colon. The two are equal as JSON and different as
# bytes, so a pattern assuming one of the two spellings works until the stack is brought up twice.
first_id() {
    sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

account() {
    post /v1/accounts "demo-account-$2" "{\"account_type\":\"$1\",\"owner_ref\":\"$2\"}" | first_id
}

# Read rather than assumed. The funding is idempotent, so a second bring-up adds nothing - and by
# then the demo transfer may well have been run, which a hard-coded figure would contradict.
balance() {
    curl -sS "$API/v1/accounts/$1" | sed -n 's/.*"balance"[^0-9-]*\([-0-9][0-9]*\).*/\1/p'
}

# Money enters the ledger from EQUITY, which is the one account type allowed to go negative. The
# funding transaction is balanced like any other; nothing is created out of nothing here either.
equity=$(account EQUITY demo-equity)
alice=$(account LIABILITY demo-alice)
bob=$(account LIABILITY demo-bob)

post /v1/funding demo-funding \
    "{\"from_account\":\"$equity\",\"to_account\":\"$alice\",\"amount\":1000000,\"description\":\"Demo funding\"}" \
    > /dev/null

cat <<BANNER

  The ledger is up, with two demo accounts and money in one of them.
  Balances are in kurus, which is the only unit this API speaks.

    alice  $alice   $(balance "$alice")
    bob    $bob   $(balance "$bob")

  One request, 12.50 TRY from alice to bob. It is one line rather than five so that it
  survives being copied out of a container log:

curl -i -X POST http://127.0.0.1:8080/v1/transfers -H 'Content-Type: application/json' -H 'X-Client-Id: demo' -H 'Idempotency-Key: first-transfer' -d '{"from_account":"$alice","to_account":"$bob","amount":1250,"description":"Coffee"}'

  Run it twice: the second call returns the first call's transaction rather than making a
  second one. Change the Idempotency-Key and it becomes a new transfer.

BANNER
