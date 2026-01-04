# Service: Inventory Service

## Alert: Database Connection Timeout

### 1. Detection Logic (ELF Query)
**Time Period:** 30 minutes
**Query:**
```lucene
service:"inventory-service" AND log.message:"Connection check failed" AND db.type:postgres
``` 

## Alert: Elevated 5xx Error Rate

### 1. Detection Logic (ELF Query)
**Time Period:** 1 hour
**Query:**
```lucene
service:"inventory-service" AND status_code:[500 TO 599] AND log.level:ERROR
``` 
