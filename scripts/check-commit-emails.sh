#!/usr/bin/env bash
# Checks that the author and the committer of every commit in a range have a GitHub noreply
# address. The repository is public: a personal or a work email must never end up in its history.
#
# Usage: scripts/check-commit-emails.sh [<base>..<head>]   (default: origin/main..HEAD)
#
# On failure it prints the commits (short hash only, never the address: the log of a public
# repository is public too). Needs the history of the range, so in CI the checkout is complete.
set -euo pipefail

range="${1:-origin/main..HEAD}"
allowed='^([0-9]+\+[A-Za-z0-9-]+@users\.noreply\.github\.com|noreply@github\.com)$'
bad=0

# In a variable, not in a process substitution: if git cannot read the range (missing history, a
# wrong name) the script must stop with an error, not pass because it found nothing to check.
log="$(git log --format='%h %ae %ce' "$range")"

while read -r commit author committer; do
  [[ -n "$commit" ]] || continue
  if ! [[ "$author" =~ $allowed && "$committer" =~ $allowed ]]; then
    echo "Commit $commit: the author or the committer is not a GitHub noreply address." >&2
    bad=1
  fi
done <<< "$log"

if [[ "$bad" -ne 0 ]]; then
  echo "Set it in this repository: git config user.email \"<id>+<user>@users.noreply.github.com\" (see docs/04-ambiente.md)." >&2
  exit 1
fi
echo "OK: every commit of $range has a GitHub noreply address."
