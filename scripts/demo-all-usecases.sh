#!/usr/bin/env bash
#
# demo-all-usecases.sh — drive EVERY Event Reliability Platform use case against a running backend.
#
# Usage:
#   ./scripts/demo-all-usecases.sh [BASE_URL]
#   BASE_URL defaults to http://localhost:8080
#
# Prereqs: the backend running (ideally with the `demo` profile so incidents/anomalies trip on a
# handful of events), curl. `jq` and `python3` are optional (used for pretty-printing + the
# maker-checker step). Nothing here needs the Flutter console or the sample app — it uses the HTTP API
# directly, so it works as a smoke test of the whole platform.
#
set -uo pipefail

BASE="${1:-http://localhost:8080}"
ACTOR_MAKER="alice"
ACTOR_CHECKER="bob"

have()      { command -v "$1" >/dev/null 2>&1; }
hr()        { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
note()      { printf '   \033[0;33m%s\033[0m\n' "$*"; }
show()      { if have jq; then jq -C . 2>/dev/null || cat; elif have python3; then python3 -m json.tool 2>/dev/null || cat; else cat; fi; }

# GET helper:  get <path>
get()       { curl -s -H "X-Actor: $ACTOR_MAKER" "$BASE$1"; }
# POST JSON:   post <path> <json> [actor]
post()      { curl -s -H "X-Actor: ${3:-$ACTOR_MAKER}" -H 'Content-Type: application/json' -X POST "$BASE$1" -d "$2"; }

# Report a failure over the HTTP "all-error" intake.  intake <source> <exceptionClass> <payloadJson> [correlationId]
intake() {
  local source="$1" ex="$2" payload="$3" corr="${4:-}"
  local body
  if [ -n "$corr" ]; then
    body=$(printf '{"correlationId":"%s","source":"%s","sourceApp":"demo-script","exceptionClass":"%s","payload":%s}' "$corr" "$source" "$ex" "$payload")
  else
    body=$(printf '{"source":"%s","sourceApp":"demo-script","exceptionClass":"%s","payload":%s}' "$source" "$ex" "$payload")
  fi
  post "/api/failures" "$body"
}

# ---------------------------------------------------------------------------------------------------
hr "0. Preflight — backend health ($BASE)"
if ! curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
  note "Backend not reachable at $BASE. Start it:  docker compose up -d  &&  (cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=demo)"
  exit 1
fi
get /actuator/health | show

# ---------------------------------------------------------------------------------------------------
hr "1. Failures + classification (Failures tab)"
note "One failure per lane; the classifier routes each by its exception class."
intake "payments.transactions" "org.example.PaymentGatewayTimeoutException" '"{\"paymentId\":\"PAY-1\"}"' >/dev/null
intake "billing.debits"        "com.example.billing.BusinessException"      '"{\"account\":\"ACC-1\"}"'  >/dev/null
intake "inventory.updates"     "com.fasterxml.jackson.databind.exc.MismatchedInputException" '"{\"qty\":\"NaN\"}"' >/dev/null
intake "sagas.events"          "java.lang.IllegalStateException"           '"{\"sagaId\":\"S-1\"}"'     >/dev/null
note "GET /api/failures (latest):"
get "/api/failures?size=5" | show

# ---------------------------------------------------------------------------------------------------
hr "2. PII masking (Failure detail)"
PII_ID="pii-$(date +%s)"
intake "customer.events" "com.bank.kyc.ValidationException" \
  '"{\"name\":\"Jane Doe\",\"ssn\":\"123-45-6789\",\"email\":\"jane@acme-bank.com\",\"card\":\"4111-1111-1111-1111\",\"iban\":\"GB29NWBK60161331926819\"}"' \
  "$PII_ID" >/dev/null
sleep 2
note "payloadBase64 returned by the API, decoded — PII is replaced with [MASKED:*]:"
PAYLOAD_B64=$(get "/api/failures/$PII_ID" | { have jq && jq -r '.payloadBase64' || cat; })
if have base64 && [ -n "${PAYLOAD_B64:-}" ] && [ "$PAYLOAD_B64" != "null" ]; then
  echo "$PAYLOAD_B64" | base64 -d 2>/dev/null || echo "$PAYLOAD_B64"
  echo
else
  note "(install jq + base64 to auto-decode; otherwise open $PII_ID in the console)"
fi

# ---------------------------------------------------------------------------------------------------
hr "3. Financial exposure / value-at-risk (Exposure tab)"
note "Failures carrying an amount + currency; Exposure sums them per currency."
intake "settlement.events" "com.bank.SettlementException" '"{\"amount\":1000.50,\"currency\":\"USD\"}"' >/dev/null
intake "settlement.events" "com.bank.SettlementException" '"{\"amount\":250,\"currency\":\"USD\"}"'      >/dev/null
intake "orders.events"     "com.bank.SettlementException" '"{\"transactionAmount\":\"5000.00\",\"currency\":\"EUR\"}"' >/dev/null
sleep 2
get /api/exposure | show

# ---------------------------------------------------------------------------------------------------
hr "4. Incidents — systemic failure detection (Incidents tab)"
note "A storm sharing one root cause. With the demo profile (threshold 5) this raises an incident."
for i in $(seq 1 8); do
  intake "ledger.events" "com.bank.SchemaDriftException" '"{\"n\":'"$i"'}"' >/dev/null
done
sleep 3
get /api/incidents | show

# ---------------------------------------------------------------------------------------------------
hr "5. Adaptive anomaly detection (Anomalies tab)"
note "A burst of a brand-new signature -> flagged as novelty against an empty baseline."
NOVEL="com.bank.NovelException$(date +%s)"
for i in $(seq 1 8); do
  intake "treasury.events" "$NOVEL" '"{\"n\":'"$i"'}"' >/dev/null
done
sleep 2
get /api/anomalies | show

# ---------------------------------------------------------------------------------------------------
hr "6. Reconciliation — backlog completeness (Reconcile tab)"
get /api/reconciliation | show

# ---------------------------------------------------------------------------------------------------
hr "7. Declared-expectation reconciliation (Reconcile -> Declared batches)"
note "Declare 'expect 100 on settlement.events'; the shortfall = the stuck failures from step 3."
post "/api/reconciliation/expectations" \
  '{"key":"eod-demo","source":"settlement.events","expectedCount":100,"label":"EOD settlement demo"}' >/dev/null
sleep 1
get /api/reconciliation/expectations | show

# ---------------------------------------------------------------------------------------------------
hr "8. Trends / analytics (Trends tab)"
get /api/trends | show

# ---------------------------------------------------------------------------------------------------
hr "9. Maker-checker replay (4-eyes) (Approvals tab)"
RID="recon-mc-$(date +%s)"
intake "payments.transactions" "org.example.PaymentGatewayTimeoutException" '"{\"replay\":\"me\"}"' "$RID" >/dev/null
sleep 2
note "Maker ($ACTOR_MAKER) requests a replay:"
post "/api/failures/$RID/replay" '{"reason":"upstream fixed"}' "$ACTOR_MAKER" | show
if have jq; then
  REQ_ID=$(get "/api/approvals?status=PENDING" | jq -r '.[-1].requestId // empty')
  if [ -n "$REQ_ID" ]; then
    note "Same user cannot approve (expect 403):"
    curl -s -o /dev/null -w '   HTTP %{http_code}\n' -H "X-Actor: $ACTOR_MAKER" -H 'Content-Type: application/json' \
      -X POST "$BASE/api/approvals/$REQ_ID/approve" -d '{"reason":"me"}'
    note "Different checker ($ACTOR_CHECKER) approves -> executes:"
    post "/api/approvals/$REQ_ID/approve" '{"reason":"verified"}' "$ACTOR_CHECKER" | show
  fi
else
  note "(install jq to auto-approve; otherwise: GET /api/approvals then POST /api/approvals/{id}/approve as $ACTOR_CHECKER)"
fi

# ---------------------------------------------------------------------------------------------------
hr "10. Compliance export (governance)"
note "JSON register (first rows):"
get "/api/compliance/export" | show | head -40
note "CSV (header + first rows):"
get "/api/compliance/export?format=csv" | head -5

# ---------------------------------------------------------------------------------------------------
hr "11. Operations assistant (Ask Brod)"
note "Only answers when a self-hosted model is configured (reliability.assistant.*); otherwise replies 'not configured'."
post "/api/assistant/ask" '{"question":"why are payments failing?"}' | show

# ---------------------------------------------------------------------------------------------------
hr "Done"
note "Open the console and walk the tabs: Dashboard, Failures, Incidents, Anomalies, Trends, Exposure,"
note "Reconcile, Ask Brod, Approvals, Audit. Re-run any section above to add more data."
note "Audit one message:  GET $BASE/api/failures/$PII_ID"
