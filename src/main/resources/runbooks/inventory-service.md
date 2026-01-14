# Service: Inventory Service

## Alert: Database Connection Timeout

### 1. Detection Logic (ELF Query)
**Time Period:** 30 minutes
**Query:**
```lucene
application.name:"inventory-service" AND log.message:"Connection check failed" AND db.type:postgres
```  

## 2. Remediation
**1. Call healthCheck for inventory-service to determine if the service is completely down.**

**2.Query logs for the specific error pattern: HikariPool-1 - Connection is not available.**

**3.Scenario A (Pool Exhaustion): If log matches > 50 in the last 5 minutes, this is a connection leak. Action: Restart the inventory-service pods to clear the pool.**

**4.Scenario B (Network): If logs show SocketTimeoutException connecting to inv-db-prod, verify if the database is in maintenance mode.**

**5. If the database is reachable but latency is high, Escalate to the Database Reliability Team (Slack: #dba-urgent).**

----

## Alert: Elevated 5xx Error Rate

### 1. Detection Logic (ELF Query)
**Time Period:** 1 hour
**Query:**
```lucene
application.name:"inventory-service" AND status_code:[500 TO 599] AND log.level:ERROR
```   

### 2. Remediation
**1. Check if a deployment occurred in the last 30 minutes. If YES, recommended action is to Rollback.**

**2. Query logs for the specific business exception: StockCountMismatchException.**

**3. If Mismatch Found: Do not page on-call. Trigger the automated job: Jenkins / Inventory / Reconcile-Stock.**

**4. If NullPointerException: This indicates a code bug. Escalate to Supply Chain Engineering immediately.**

**5. If the healthCheck returns "UP" but errors persist > 5%, assume a downstream dependency failure (e.g., Warehouse API).**

----

## Alert: Cache Inconsistency
### 1. Detection Logic (ELF Query)
**Time Period:** 15 minutes
**Query:**
```lucene
application.name:"inventory-service" AND log.message:"Cache key miss" AND db.status:"UP"
``` 
### 2. Remediation
**1. Verify Database is healthy using healthCheck tool.**

**2. If Database is UP but users see old data, flush the Redis cache.**

**3. Command: redis-cli -h inv-cache-prod FLUSHALL.**

**4. Restart the inventory-service to repopulate local caffeine caches.**
