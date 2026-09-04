#!/usr/bin/env bash
# ci/check-rules.sh
set -uo pipefail
violation=0

deny() {
  local desc="$1"; shift
  local out status
  out=$(grep -rn "$@" 2>&1)
  status=$?
  case $status in
    0)
      echo "RULE VIOLATION: $desc"
      echo "$out" | head -20
      violation=1
      ;;
    1)
      : # no match, clean
      ;;
    *)
      # grep could not complete the search - most often a path in the list does
      # not exist. It exits 2 EVEN WHEN IT FOUND MATCHES, so treating a non-zero
      # status as "clean" would silently disable the check. Fail loudly instead.
      echo "GUARD ERROR: could not run check '$desc' (grep exit $status)"
      echo "$out" | head -5
      violation=1
      ;;
  esac
}

deny "floating point or BigDecimal in domain/service" \
  -E '\b(double|float|BigDecimal)\b' --include='*.java' \
  src/main/java/com/baran/ledger/domain src/main/java/com/baran/ledger/service

deny "@Transactional in a concurrency or idempotency test" \
  '@Transactional' --include='*Concurrency*.java' --include='*Idempotency*.java' src/test/java

deny "TODO/FIXME left in source" \
  -E 'TODO|FIXME' --include='*.java' --include='*.sql' src

deny "AI tool reference in committed content" \
  -iE 'co-authored-by|generated (by|with)|anthropic|copilot|chatgpt' \
  --exclude='CLAUDE.md' --exclude='PHASES.md' --exclude='PROGRESS.md' \
  src docs README.md

deny "JPA/Hibernate dependency present" \
  -E 'starter-data-jpa|hibernate' pom.xml

deny "optimistic locking version column" \
  -iE '\bversion\b\s+BIGINT' --include='*.sql' src/main/resources/db/migration

exit $violation
