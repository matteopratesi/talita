#!/usr/bin/env bash
# Checks that the author and the committer of every commit in a range have a GitHub noreply
# address. The repository is public: a personal or a work email must never end up in its history.
#
# Allowed, and nothing else:
#  - <id>+<login>@users.noreply.github.com, the noreply address of an account;
#  - <id>+<login>[bot]@users.noreply.github.com, that of a GitHub bot (github-actions, Dependabot,
#    CodeRabbit). It is public and belongs to no person, so refusing it would protect nothing and
#    would fail the CI the day a bot adds a commit to a pull request (an applied suggestion, an
#    update of a dependency). Only in this exact form: with the id, "[bot]" right before the "@",
#    and the domain ending there. Who may write to a branch is decided by the permissions on
#    GitHub, not by this script;
#  - the address GitHub itself uses as committer (noreply at github.com): the squash of a pull
#    request and the "Update branch" button.
# Any other address is refused, also that of a contributor from a fork who has not set their
# noreply one: the script cannot tell the address of the maintainer from another without writing
# it in the repository, so it refuses every address that is not a noreply.
#
# Usage: scripts/check-commit-emails.sh [<base>..<head>]   (default: origin/main..HEAD)
#
# On failure it prints the commits (short hash only, never the address: the log of a public
# repository is public too). Needs the history of the range, so in CI the checkout is complete.
set -euo pipefail

range="${1:-origin/main..HEAD}"
allowed='^([0-9]+\+[A-Za-z0-9-]+(\[bot\])?@users\.noreply\.github\.com|noreply@github\.com)$'
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
