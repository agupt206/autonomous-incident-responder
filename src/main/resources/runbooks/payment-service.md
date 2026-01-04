# Service: Payment Service
## Alert: Elevated 5xx Error Rate

### 1. Detection Logic (ELF Query)
**Time Period:** 1 hour
**Query:**
```lucene
type:opentracing-log AND log.level:ERROR AND application.name:"payment-service" AND status_code:[500 TO 599]
```   
## Alert: Upstream Gateway Latency
### 1. Detection Logic
**Query:**
```lucene
service:"payment-service" AND metric:latency AND value > 2000
``` 
