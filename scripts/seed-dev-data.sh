#!/usr/bin/env bash
# Seeds dummy data into a running local stack via the real REST API (not raw SQL), so every
# invariant the app enforces (password hashing, status transitions, role checks, moderation
# workflow) is exercised the same way a real client would trigger it.
#
# Usage:
#   ./scripts/seed-dev-data.sh
#   BASE_URL=http://localhost:8080/api/v1 ./scripts/seed-dev-data.sh
#
# Requires: curl, jq. Requires an admin account to exist — bootstrap one first if needed:
#   curl -X POST $BASE_URL/auth/admin/bootstrap -H "Content-Type: application/json" \
#     -d '{"bootstrapToken":"<ADMIN_BOOTSTRAP_TOKEN from .env>","email":"admin@tinniestudio.com","password":"AdminPass123!"}'

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@tinniestudio.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-AdminPass123!}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

log() { echo "[seed] $*"; }

# jar <name> — path to a dedicated cookie jar so each persona's session stays isolated
jar() { echo "$TMP_DIR/cookies-$1.txt"; }

# api <jar-name> <method> <path> <json-body>  — prints response body, exits non-zero on network error
api() {
  local jarname="$1" method="$2" path="$3" body="${4:-}"
  local args=(-s -X "$method" "$BASE_URL$path" -H "Content-Type: application/json" -c "$(jar "$jarname")" -b "$(jar "$jarname")")
  if [ -n "$body" ]; then args+=(-d "$body"); fi
  curl "${args[@]}"
}

require_ok() {
  local resp="$1" step="$2"
  if [ "$(echo "$resp" | jq -r '.success // "null"')" != "true" ]; then
    echo "[seed] FAILED at: $step" >&2
    echo "$resp" | jq . >&2 || echo "$resp" >&2
    exit 1
  fi
}

log "Base URL: $BASE_URL"

# ── Admin session ────────────────────────────────────────────────────────────
log "Logging in as admin ($ADMIN_EMAIL)"
admin_login=$(api admin POST /auth/admin/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
require_ok "$admin_login" "admin login"

# ── Register viewers + one partner-to-be ────────────────────────────────────
declare -a VIEWERS=(viewer1 viewer2 viewer3)
PARTNER_USER="partner1"
declare -A USER_IDS

for persona in "${VIEWERS[@]}" "$PARTNER_USER"; do
  email="${persona}+seed@tinniestudio.com"
  log "Registering $email"
  resp=$(api "$persona" POST /auth/register "{\"email\":\"$email\",\"password\":\"SeedPass1!\",\"firstName\":\"${persona^}\",\"lastName\":\"Seed\"}")
  if [ "$(echo "$resp" | jq -r '.success // "null"')" != "true" ]; then
    # Already registered from a previous run — log in instead.
    resp=$(api "$persona" POST /auth/login "{\"email\":\"$email\",\"password\":\"SeedPass1!\"}")
    require_ok "$resp" "login $persona (already registered)"
  fi
  USER_IDS[$persona]=$(echo "$resp" | jq -r '.data.userId // .data.id')
done
log "Registered/logged in ${#VIEWERS[@]} viewers + 1 partner-to-be"

# ── Partner application → admin approval ────────────────────────────────────
log "partner1 applies to become a partner"
apply_resp=$(api "$PARTNER_USER" POST /partners/applications '{"companyName":"Seed Studios","description":"Dummy content for local testing","websiteUrl":"https://seed-studios.example.com"}')
if [ "$(echo "$apply_resp" | jq -r '.success // "null"')" = "true" ]; then
  application_id=$(echo "$apply_resp" | jq -r '.data.id')
  log "Admin approving partner application $application_id"
  approve_resp=$(api admin POST "/admin/partner-applications/$application_id/approve" "")
  require_ok "$approve_resp" "approve partner application"
else
  log "partner1 already has a pending/approved application — skipping apply step"
fi

# Re-login partner1 so the JWT picks up the newly granted ROLE_PARTNER
log "Refreshing partner1 session to pick up ROLE_PARTNER"
api "$PARTNER_USER" POST /auth/login "{\"email\":\"partner1+seed@tinniestudio.com\",\"password\":\"SeedPass1!\"}" >/dev/null

# ── Category lookup ──────────────────────────────────────────────────────────
categories_resp=$(curl -s "$BASE_URL/categories")
action_id=$(echo "$categories_resp" | jq -r '.data[] | select(.slug=="action") | .id')
drama_id=$(echo "$categories_resp" | jq -r '.data[] | select(.slug=="drama") | .id')
comedy_id=$(echo "$categories_resp" | jq -r '.data[] | select(.slug=="comedy") | .id')

# ── Content: two movies, two series (with seasons/episodes) ─────────────────
publish_content() {
  local content_id="$1"
  api admin POST "/admin/contents/$content_id/submit" "" >/dev/null
  api admin POST "/admin/contents/$content_id/approve" "" >/dev/null
  api admin POST "/admin/contents/$content_id/publish" "" >/dev/null
}

log "Creating movie: 'The Seed Job'"
movie1=$(api "$PARTNER_USER" POST /admin/contents "{\"title\":\"The Seed Job\",\"type\":\"MOVIE\",\"maturityRating\":\"PG_13\",\"description\":\"A heist movie for load-testing.\",\"shortDescription\":\"Heist movie\",\"durationSeconds\":6300,\"categoryIds\":[\"$action_id\"]}")
require_ok "$movie1" "create movie 1"
movie1_id=$(echo "$movie1" | jq -r '.data.id')
publish_content "$movie1_id"
log "Published movie 'The Seed Job' ($movie1_id)"

log "Creating movie: 'Laughing Matter'"
movie2=$(api "$PARTNER_USER" POST /admin/contents "{\"title\":\"Laughing Matter\",\"type\":\"MOVIE\",\"maturityRating\":\"PG\",\"description\":\"A comedy for load-testing.\",\"shortDescription\":\"Comedy\",\"durationSeconds\":5400,\"categoryIds\":[\"$comedy_id\"]}")
require_ok "$movie2" "create movie 2"
movie2_id=$(echo "$movie2" | jq -r '.data.id')
publish_content "$movie2_id"
log "Published movie 'Laughing Matter' ($movie2_id)"

log "Creating series: 'Seed Chronicles'"
series1=$(api "$PARTNER_USER" POST /admin/contents "{\"title\":\"Seed Chronicles\",\"type\":\"SERIES\",\"maturityRating\":\"TV_14\",\"description\":\"A drama series for load-testing.\",\"shortDescription\":\"Drama series\",\"categoryIds\":[\"$drama_id\"]}")
require_ok "$series1" "create series 1"
series1_id=$(echo "$series1" | jq -r '.data.id')

for s in 1 2; do
  season_resp=$(api "$PARTNER_USER" POST "/admin/contents/$series1_id/seasons" "{\"seasonNumber\":$s,\"title\":\"Season $s\"}")
  season_id=$(echo "$season_resp" | jq -r '.data.id')
  for e in 1 2 3; do
    api "$PARTNER_USER" POST "/seasons/$season_id/episodes" "{\"episodeNumber\":$e,\"title\":\"S${s}E${e}\",\"durationSeconds\":2700}" >/dev/null
  done
  log "Season $s of 'Seed Chronicles' created with 3 episodes"
done
publish_content "$series1_id"
log "Published series 'Seed Chronicles' ($series1_id)"

# ── Viewer activity: favorites + reviews ─────────────────────────────────────
for persona in "${VIEWERS[@]}"; do
  api "$persona" POST "/favorites/$movie1_id" "" >/dev/null || true
  api "$persona" POST "/favorites/$series1_id" "" >/dev/null || true
done
log "Viewers favorited movie1 + series1"

api viewer1 POST "/contents/$movie1_id/reviews" '{"rating":5,"body":"Loved the heist twist — seed data review."}' >/dev/null
api viewer2 POST "/contents/$movie1_id/reviews" '{"rating":4,"body":"Solid, would watch again."}' >/dev/null
api viewer1 POST "/contents/$movie2_id/reviews" '{"rating":3,"body":"A few good laughs."}' >/dev/null
log "Viewers left reviews on movie1 and movie2"

log "Done. Summary:"
echo "  Admin:    $ADMIN_EMAIL"
echo "  Partner:  partner1+seed@tinniestudio.com / SeedPass1!"
echo "  Viewers:  viewer1/2/3+seed@tinniestudio.com / SeedPass1!"
echo "  Content:  movie '$movie1_id', movie '$movie2_id', series '$series1_id' (all PUBLISHED)"
