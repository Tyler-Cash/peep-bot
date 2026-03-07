#!/usr/bin/env bash
#
# Seed sample events into a running Peep Bot instance.
#
# Usage:
#   ./scripts/seed-events.sh <base-url> <session-cookie>
#
# Example:
#   ./scripts/seed-events.sh http://localhost:8080/api "abc123-session-id"
#
# To get your SESSION cookie:
#   1. Log in via the frontend (http://localhost:5173/login)
#   2. Open browser DevTools -> Application -> Cookies
#   3. Copy the value of the "SESSION" cookie

set -euo pipefail

BASE_URL="${1:?Usage: $0 <base-url> <session-cookie>}"
SESSION="${2:?Usage: $0 <base-url> <session-cookie>}"

COOKIE_FILE=$(mktemp)
trap 'rm -f "$COOKIE_FILE"' EXIT

# Fetch CSRF token
CSRF_BODY=$(curl -s -c "$COOKIE_FILE" -b "SESSION=$SESSION" "$BASE_URL/csrf")
CSRF_HEADER=$(echo "$CSRF_BODY" | jq -r '.token')
XSRF_COOKIE=$(grep XSRF-TOKEN "$COOKIE_FILE" | awk '{print $NF}')

if [ -z "$CSRF_HEADER" ] || [ "$CSRF_HEADER" = "null" ]; then
  echo "ERROR: Failed to fetch CSRF token. Check your BASE_URL and SESSION cookie."
  exit 1
fi

create_event() {
  local json="$1"
  local name
  name=$(echo "$json" | jq -r '.name')

  response=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/event" \
    -b "SESSION=$SESSION; XSRF-TOKEN=$XSRF_COOKIE" \
    -H "Content-Type: application/json" \
    -H "X-XSRF-TOKEN: $CSRF_HEADER" \
    -d "$json")

  if [ "$response" -ge 200 ] && [ "$response" -lt 300 ]; then
    echo "  Created: $name"
  else
    echo "  FAILED ($response): $name"
  fi
}

echo "Seeding events into $BASE_URL ..."

create_event '{
  "name": "Blue Mountains Hike",
  "description": "Moderate 10km trail through the Three Sisters area. Meet at the carpark at 8am sharp. Bring water and sunscreen!",
  "capacity": 12,
  "cost": 0,
  "location": "Echo Point, Katoomba",
  "dateTime": "2026-03-28T08:00:00+11:00"
}'

create_event '{
  "name": "Sci-Fi Movie Marathon",
  "description": "Back-to-back sci-fi classics. Popcorn provided. Voting on the lineup happens in #movies channel.",
  "capacity": 8,
  "cost": 5,
  "location": "Tylers Place",
  "dateTime": "2026-04-04T17:00:00+11:00"
}'

create_event '{
  "name": "Spring BBQ",
  "description": "Sausage sizzle and drinks in the park. BYO meat if you want something fancy. Veggie options available!",
  "capacity": 0,
  "cost": 10,
  "location": "Centennial Park",
  "dateTime": "2026-04-12T12:00:00+10:00"
}'

create_event '{
  "name": "Pub Trivia Night",
  "description": "Teams of up to 6. First place wins a bar tab. Sign up your team name in the thread!",
  "capacity": 30,
  "cost": 0,
  "location": "The Local Pub",
  "dateTime": "2026-04-18T19:00:00+10:00"
}'

create_event '{
  "name": "Laser Tag Tournament",
  "description": "Two teams, three rounds, one winner. Losers buy pizza afterwards.",
  "capacity": 16,
  "cost": 25,
  "location": "Zone Bowling & Laser Tag",
  "dateTime": "2026-04-25T14:00:00+10:00"
}'

echo "Done!"
