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

# The range the message checks read. On a phase branch, origin/main..HEAD is
# exactly the commits that branch adds. On main itself that range is empty, so a
# check written against it would pass by looking at nothing - on the one branch
# every phase eventually lands in, and the branch both offending trailers reached.
# Fall back to the full history of HEAD there, which is what a check running on
# main should read anyway.
range='origin/main..HEAD'
if ! git rev-parse --verify --quiet origin/main >/dev/null 2>&1 ||
   [ -z "$(git rev-list --max-count=1 "$range" 2>/dev/null)" ]; then
  range='HEAD'
fi

# deny() greps the worktree, so nothing built on it can see a commit message. Two
# commits carried a Co-Authored-By trailer into main while this guard reported
# success, because no check here ever read the log.
deny_log() {
  local desc="$1"; shift
  local shas sha msg messages out status

  shas=$(git rev-list "$range" 2>&1)
  status=$?
  if [ $status -ne 0 ]; then
    echo "GUARD ERROR: could not list the commits in '$range' (git exit $status)"
    echo "$shas" | head -5
    violation=1
    return
  fi

  # Prefixing every line with its commit is what makes a hit actionable; grep -n
  # over the concatenation would only give an offset into a stream nobody has.
  messages=''
  for sha in $shas; do
    msg=$(git log -1 --format='%B' "$sha" 2>&1)
    status=$?
    if [ $status -ne 0 ]; then
      echo "GUARD ERROR: could not read the message of $sha (git exit $status)"
      echo "$msg" | head -5
      violation=1
      return
    fi
    messages+=$(printf '%s\n' "$msg" | sed "s|^|${sha:0:9} |")$'\n'
  done

  out=$(printf '%s' "$messages" | grep "$@")
  status=$?
  case $status in
    0)
      echo "RULE VIOLATION: $desc (range $range)"
      echo "$out" | head -20
      violation=1
      ;;
    1)
      : # no match, clean
      ;;
    *)
      # The same distinction deny() draws: any status but 0 or 1 means the search
      # did not run, which is not the same answer as "found nothing".
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
  --exclude='CONVENTIONS.md' --exclude='roadmap.md' --exclude='PROGRESS.md' \
  src docs README.md

# CLAUDE.md is a filename the history legitimately names, so the tool is matched
# only where it cannot be one: a trailer, a vendor domain, a session link.
deny_log "AI tool reference in a commit message" \
  -iE 'co-authored-by|generated (by|with)|anthropic|copilot|chatgpt|claude\.ai|claude-session'

deny "JPA/Hibernate dependency present" \
  -E 'starter-data-jpa|hibernate' pom.xml

deny "optimistic locking version column" \
  -iE '\bversion\b\s+BIGINT' --include='*.sql' src/main/resources/db/migration

exit $violation
