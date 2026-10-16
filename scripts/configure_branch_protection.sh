#!/usr/bin/env bash
# Configure GitHub branch protection on `main` for PR-gated merges with
# required status checks. Requires: gh CLI authenticated with repo admin.
# Usage: ./scripts/configure_branch_protection.sh [owner/repo]
set -euo pipefail

REPO="${1:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  "/repos/${REPO}/branches/main/protection" \
  --input - <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "quality",
      "verify",
      "e2e"
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "required_approving_review_count": 0
  },
  "required_conversation_resolution": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "restrictions": null
}
EOF

echo "Branch protection configured for ${REPO}:main"
