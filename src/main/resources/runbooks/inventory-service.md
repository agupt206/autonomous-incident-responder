# Service: Inventory Service

## Alert: Database Connection Timeout

### 1. Detection Logic (ELF Query)
**Time Period:** 30 minutes
**Query:**
```lucene
service:"inventory-service" AND log.message:"Connection check failed" AND db.type:postgres
```  

## 2. Remediation
**Step #1. Call healthCheck for inventory-service to determine if the service is completely down.**

**Step #2.Query logs for the specific error pattern: HikariPool-1 - Connection is not available.**

**Step #3.Scenario A (Pool Exhaustion): If log matches > 50 in the last 5 minutes, this is a connection leak. Action: Restart the inventory-service pods to clear the pool.**

**Step #4.Scenario B (Network): If logs show SocketTimeoutException connecting to inv-db-prod, verify if the database is in maintenance mode.**

**Step #5. If the database is reachable but latency is high, Escalate to the Database Reliability Team (Slack: #dba-urgent).**

## Alert: Elevated 5xx Error Rate

### 1. Detection Logic (ELF Query)
**Time Period:** 1 hour
**Query:**
```lucene
service:"inventory-service" AND status_code:[500 TO 599] AND log.level:ERROR
```   

### 2. Remediation
**Step #1. Check if a deployment occurred in the last 30 minutes. If YES, recommended action is to Rollback.**

**Step #2. Query logs for the specific business exception: StockCountMismatchException.**

**Step #3. If Mismatch Found: Do not page on-call. Trigger the automated job: Jenkins / Inventory / Reconcile-Stock.**

**Step #4. If NullPointerException: This indicates a code bug. Escalate to Supply Chain Engineering immediately.**

**Step #5. If the healthCheck returns "UP" but errors persist > 5%, assume a downstream dependency failure (e.g., Warehouse API).**
