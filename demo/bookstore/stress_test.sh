#!/usr/bin/env bash
# Usage: ./stress_test.sh [base-url] [requests-per-second]
# Default: http://localhost:8080, 10 req/s
# Press Ctrl-C to stop.

set -euo pipefail

BASE="${1:-http://localhost:8080}"
RPS="${2:-10}"
SLEEP=$(awk "BEGIN {printf \"%.4f\", 1/$RPS}")

declare -a IDS=()

# ── helpers ────────────────────────────────────────────────────────────────

request() {
  local method="$1" path="$2" body="${3:-}"
  local url="$BASE$path"
  local start end status response elapsed

  start=$(date +%s%N)
  if [[ -n "$body" ]]; then
    response=$(curl -s -o /tmp/_bs_body -w "%{http_code}" \
      -X "$method" -H "Content-Type: application/json" \
      -d "$body" "$url")
  else
    response=$(curl -s -o /tmp/_bs_body -w "%{http_code}" \
      -X "$method" "$url")
  fi
  end=$(date +%s%N)
  elapsed=$(awk "BEGIN {printf \"%.3f\", ($end - $start) / 1000000000}")

  printf "%-7s %-35s %s  %ss\n" "$method" "$path" "$response" "$elapsed"
}

list_books() {
  request GET "/books"
}

get_book() {
  local id="$1"
  request GET "/books/$id"
}

similar() {
  local id="$1"
  request GET "/books/$id/similar"
}

create_book() {
  local title author tag price
  title="Book-$(shuf -n1 -e Alpha Beta Gamma Delta Epsilon Zeta Eta Theta)-$$-$RANDOM"
  author="Author-$(shuf -n1 -e Smith Jones Brown Davis Wilson)"
  tag=$(shuf -n1 -e programming design patterns algorithms career software theory)
  price=$(awk "BEGIN {printf \"%.2f\", 10 + $RANDOM % 90}")

  local body
  body=$(printf '{"title":"%s","author":"%s","tags":["%s"],"price":%s}' \
    "$title" "$author" "$tag" "$price")

  local response
  response=$(curl -s -o /tmp/_bs_body -w "%{http_code}" \
    -X POST -H "Content-Type: application/json" \
    -d "$body" "$BASE/books")

  printf "%-7s %-35s %s\n" "POST" "/books" "$response"

  if [[ "$response" == "201" ]]; then
    local new_id
    new_id=$(python3 -c "import json,sys; d=json.load(open('/tmp/_bs_body')); print(d.get('id',''))" 2>/dev/null || true)
    [[ -n "$new_id" ]] && IDS+=("$new_id")
  fi
}

search_books() {
  local strategies=("title" "author" "price-range")
  local by
  by=$(shuf -n1 -e "${strategies[@]}")

  case "$by" in
    title)
      local terms=("Code" "Design" "Programming" "Pragmatic" "Clean" "Structure" "Algorithms")
      local q
      q=$(shuf -n1 -e "${terms[@]}")
      request GET "/books/search?by=title&q=$q"
      ;;
    author)
      local authors=("Martin" "Fowler" "Thomas" "Brooks" "Evans" "Feathers")
      local q
      q=$(shuf -n1 -e "${authors[@]}")
      request GET "/books/search?by=author&q=$q"
      ;;
    price-range)
      local min max
      min=$(awk "BEGIN {printf \"%.2f\", 10 + $RANDOM % 40}")
      max=$(awk "BEGIN {printf \"%.2f\", $min + 10 + $RANDOM % 50}")
      request GET "/books/search?by=price-range&min=$min&max=$max"
      ;;
  esac
}

update_book() {
  local id="$1"
  local price
  price=$(awk "BEGIN {printf \"%.2f\", 10 + $RANDOM % 90}")
  request PUT "/books/$id" "{\"price\":$price}"
}

delete_book() {
  local id="$1"
  request DELETE "/books/$id"
  # remove from pool
  local new_ids=()
  for i in "${IDS[@]}"; do
    [[ "$i" != "$id" ]] && new_ids+=("$i")
  done
  IDS=("${new_ids[@]+"${new_ids[@]}"}")
}

random_id() {
  local len="${#IDS[@]}"
  if [[ "$len" -eq 0 ]]; then
    echo ""
  else
    echo "${IDS[$((RANDOM % len))]}"
  fi
}

# ── seed ───────────────────────────────────────────────────────────────────

echo "Seeding 5 books..."
for _ in 1 2 3 4 5; do
  create_book
done
echo "Seed IDs: ${IDS[*]}"
echo ""
echo "Starting stress test at ${RPS} req/s against $BASE"
echo "Press Ctrl-C to stop."
echo ""

# ── main loop ──────────────────────────────────────────────────────────────

while true; do
  # replenish pool if too small
  if [[ "${#IDS[@]}" -lt 3 ]]; then
    create_book
  fi

  roll=$((RANDOM % 100))
  id=$(random_id)

  if [[ -z "$id" ]]; then
    list_books
  elif [[ $roll -lt 40 ]]; then
    get_book "$id"
  elif [[ $roll -lt 50 ]]; then
    search_books
  elif [[ $roll -lt 70 ]]; then
    similar "$id"
  elif [[ $roll -lt 85 ]]; then
    create_book
  elif [[ $roll -lt 95 ]]; then
    update_book "$id"
  else
    delete_book "$id"
  fi

  sleep "$SLEEP"
done
