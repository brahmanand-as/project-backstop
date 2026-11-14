# Project Backstop

Backstop accepts synthetic acquiring events and exposes a risk queue with versioned hold and release decisions. The queue shows refund and chargeback counts, ratios, open flags, and recent audit entries.

## Run

Requires Java 17+, Maven, Node.js 18, and Docker Compose for local infrastructure. Copy `.env.example` to `.env` and set both passwords. Start MySQL and Redis with `docker compose up -d`. Set the same `DB_PASSWORD` and `DEMO_PASSWORD` in the API process environment or your IntelliJ run configuration, then run `mvn spring-boot:run`. Compose reads .env automatically for its services. The Maven process needs the values in its own environment.

From `ui/`, run `npm ci`, then `npm start` and open http://localhost:4200. Use analyst1, analyst2 or supervisor1 with the configured demo password. The ingest account is for API clients. Populate a fresh database with `python scripts/feed.py showcase --run showcase` after exporting DEMO_PASSWORD for the script.

## API examples

Set `DEMO_PASSWORD` in your shell. The examples below use Bash syntax.

```bash
curl -u "ingest:$DEMO_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"eventId":"test-1","merchantId":"merchant-1","type":"SALE","amount":100,"currency":"INR","occurredAt":"2026-11-14T06:30:00Z"}' \
  http://localhost:8080/api/merchant-events
curl -u "analyst1:$DEMO_PASSWORD" 'http://localhost:8080/api/merchants?flagged=true&status=ON_HOLD&page=0&size=25'
curl -u "analyst1:$DEMO_PASSWORD" http://localhost:8080/api/merchants/merchant-1
curl -u "analyst1:$DEMO_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"version":0,"reason":"Review unusual refund activity"}' http://localhost:8080/api/merchants/merchant-1/hold
curl -u "supervisor1:$DEMO_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"version":1,"reason":"Completed merchant evidence review"}' http://localhost:8080/api/merchants/merchant-1/release
```

## Architecture

The merchant row owns only the control status and version. Hold and release use a guarded SQL update conditioned on both displayed version and legal starting status. Ingestion locks a separate feed row and advances only its feed revision, so fresh events cannot invalidate a decision. A successful state change and its audit insert commit together, and a rejected decision changes neither.

MySQL owns event identity through the primary key. Rolling counts use the merchant's latest event timestamp as the right edge. Effective configuration keys are `RISK_WINDOW` (PT24H), `RISK_MIN_SALES` (20), `RISK_REFUND_LIMIT` (0.15), and `RISK_CHARGEBACK_LIMIT` (0.02). An exact cutoff timestamp is included. Open flags survive subsequent lower ratios.

`RISK_STORE=redis` enables an optional Redis projection of committed read snapshots. Redis stores a compact three-count value per merchant, feed revision and window, plus key and Redis object overhead. Historical revisions expire after ten minutes, so memory depends on how many revisions are read. A Lua script atomically publishes the snapshot. Committed revision keys prevent rollback or concurrent readers from reusing an incorrect projection. Ingestion still computes flags from MySQL in the write transaction. A true incremental Redis event-time window, and performance and restart verification, remain follow-up work.

## Tests and load scripts

Run `mvn test` with no Docker, or `mvn -o test` once Maven dependencies are cached. This uses H2 in MySQL compatibility mode. The Angular UI can be checked with npm run build from ui/

Run `python scripts/feed.py seed --run seed` to send 100,000 deterministic events for 200 merchants. Expected flagged IDs are `seed-m000` through `seed-m004`. Run `python scripts/feed.py load --run load-1` for 10,000 API events at concurrency 20 and p50/p95/p99. Use fresh run identifiers and enable the Redis projection in the service environment if evaluating that configuration. No measured latency is claimed.

## Next steps

Move list filtering, risk scoring and pagination to a database query over projected counts. Implement the incremental Redis rolling store with a recovery path from MySQL and benchmark it with realistic event density. Capture UI screenshots against the running stack and exercise the live services in a separate integration profile.

## Run on Windows without Docker

In Workbench, connect to your running MySQL Server and execute:

```sql
CREATE DATABASE IF NOT EXISTS backstop_angular;
CREATE USER IF NOT EXISTS 'backstop'@'localhost' IDENTIFIED BY '<your-local-db-password>';
GRANT ALL PRIVILEGES ON backstop_angular.* TO 'backstop'@'localhost';
```

Open this project's pom.xml in IntelliJ. Select JDK 21 or 17 and configure DB_PASSWORD and DEMO_PASSWORD for the backstop.Application run configuration. Flyway seeds the schema on startup. In VS Code, open ui, run npm ci and npm start. Open http://localhost:4200. To seed, open PowerShell at the repository root, set `$env:DEMO_PASSWORD` to the same value and run `python scripts/feed.py showcase --run showcase`. Sign in as analyst1, analyst2, or supervisor1.

This is the separate Angular/MySQL edition. The Azentio submission requires React and PostgreSQL.
