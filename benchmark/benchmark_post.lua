-- wrk POST request script for JSON payloads
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.body = '{"orderId":"ORD-001","amount":150.50}'
