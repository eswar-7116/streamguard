# StreamGuard demo

`streamguard-demo` is a small shop API that uses StreamGuard exactly as any application would: it
depends on the starter and registers its routes in code
([`StreamGuardRoutesConfig`](streamguard-demo/src/main/java/io/github/eswar7116/streamguard/demo/StreamGuardRoutesConfig.java)).

## Run it

```bash
./gradlew :streamguard-demo:bootRun
```

Open **http://localhost:8080/streamguard/**.

A built-in traffic simulator sends real HTTP requests to the shop API, about 65 per second from 25
simulated clients, and scripts an incident every 40 seconds, cycling through:

| Incident | What you see |
|---|---|
| 80% of one route's requests fail for 30 s | `ERROR_SPIKE` in the anomaly feed, the route's error rate turns red. Too short to open a sustained `ERROR_RATE_HIGH` alert. |
| 20 failed logins from `203.0.113.7` | `BRUTE_FORCE` on `/api/auth/login` under active threats |
| 25 requests for paths like `/.env` and `/wp-admin` from `198.51.100.23` | `SCANNER` under active threats; the misses are grouped as `NOT_FOUND` |

Simulated client IPs travel in `X-Forwarded-For`, which the demo trusts
(`streamguard.filter.trust-forwarded-for: true`). Only enable that behind a proxy you control.

## Drive it by hand

Turn the simulator off to see only your own requests:

```bash
DEMO_TRAFFIC=false ./gradlew :streamguard-demo:bootRun
```

```bash
# Shop API
curl localhost:8080/api/products
curl localhost:8080/api/orders/42                                 # recorded as /api/orders/{id}
curl -X POST localhost:8080/api/orders
curl -X POST localhost:8080/api/payments                          # ~5% fail with 502

# Brute force: more than 10 failed logins in a minute from one client
for i in $(seq 1 12); do curl -s -o /dev/null -X POST "localhost:8080/api/auth/login?user=mallory"; done

# Scanner: more than 20 distinct unknown paths in a minute from one client
for i in $(seq 1 25); do curl -s -o /dev/null "localhost:8080/probe-$i"; done
```

## Read the API

```bash
curl -s "localhost:8080/streamguard/api/routes?window=1m" | jq
curl -s "localhost:8080/streamguard/api/routes?route=/api/orders/{id}&window=5m" -g | jq
curl -s "localhost:8080/streamguard/api/routes/top?window=5m&limit=3" | jq
curl -s  localhost:8080/streamguard/api/anomalies | jq
curl -s  localhost:8080/streamguard/api/availability | jq
curl -s  localhost:8080/streamguard/api/forecast | jq
curl -s  localhost:8080/streamguard/api/security/threats | jq
curl -s "localhost:8080/streamguard/api/security/suspicious-ips?window=1m" | jq
```

## Alert webhooks

Point alerts at a local sink to watch them arrive:

```bash
nc -kl 9000                                       # terminal 1: prints each POST
ALERT_WEBHOOK_URL=http://localhost:9000/alert ./gradlew :streamguard-demo:bootRun   # terminal 2
```

The next error-spike incident POSTs an `ERROR_SPIKE` JSON payload to the sink. `nc` never sends an
HTTP response, so StreamGuard logs a webhook timeout after 3 seconds; the payload has still arrived.
