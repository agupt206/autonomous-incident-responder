# Service: Payment Service
## Alert: Elevated 5xx Error Rate

### 1. Detection Logic (ELF Query)
**Time Period:** 1 hour
**Query:**
```lucene
type:opentracing-log AND log.level:ERROR AND application.name:"payment-service" AND status_code:[500 TO 599]
```   

### 2. Remediation
**1. Check the healthCheck tool for "DOWN" status.**

**2.If the service is "UP", query the logs to identify the specific exception type.**

**3.If the exception is PaymentDeclinedException, this is a valid business error; do not page.**

**4.If the exception is NullPointerException, roll back the last deployment immediately.**

----

## Alert: Upstream Gateway Latency
### 1. Detection Logic
**Query:**
```lucene
service:"payment-service" AND metric:latency AND value > 2000
```   

### 2. Remediation
**1.Check the healthCheck tool for "DOWN" status.**

**2. If health is UP, escalate to the Payment Service Team via Slack channel #payment-platform.**

**3. Do not restart the payment service, as this will drop active transactions.**
