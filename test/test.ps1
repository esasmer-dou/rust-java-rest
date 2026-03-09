# Windows PowerShell Test Script
# Rust-Spring Performance API Testleri

$BaseUrl = "http://localhost:8080"

Write-Host "=== Rust-Spring Performance API Tests ===" -ForegroundColor Green
Write-Host "Base URL: $BaseUrl" -ForegroundColor Cyan
Write-Host ""

# 1. Health Check
Write-Host "1. Health Check (GET /health)" -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get
    Write-Host "   Response: $($response | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "   Error: $_" -ForegroundColor Red
}
Write-Host ""

# 2. Get Order Info
Write-Host "2. Get Order Info (GET /order/order)" -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$BaseUrl/order/order" -Method Get
    Write-Host "   Response: $($response | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "   Error: $_" -ForegroundColor Red
}
Write-Host ""

# 3. Create Order
Write-Host "3. Create Order (POST /order/create)" -ForegroundColor Yellow
try {
    $body = @{
        orderId = "ORD-$(Get-Random)"
        amount = 150.50
        paid = $false
    } | ConvertTo-Json

    $headers = @{
        "Content-Type" = "application/json"
    }

    $response = Invoke-RestMethod -Uri "$BaseUrl/order/create" -Method Post -Body $body -Headers $headers
    Write-Host "   Request: $body" -ForegroundColor Gray
    Write-Host "   Response: $($response | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "   Error: $_" -ForegroundColor Red
}
Write-Host ""

# 4. Get Order By ID (Pattern Route)
Write-Host "4. Get Order By ID (GET /order/{id})" -ForegroundColor Yellow
try {
    $orderId = "12345"
    $response = Invoke-RestMethod -Uri "$BaseUrl/order/$orderId" -Method Get
    Write-Host "   Response: $($response | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "   Error: $_" -ForegroundColor Red
}
Write-Host ""

# 5. Search Orders (Query String)
Write-Host "5. Search Orders (GET /order/search?status=pending&page=1)" -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$BaseUrl/order/search?status=pending&page=1" -Method Get
    Write-Host "   Response: $($response | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "   Error: $_" -ForegroundColor Red
}
Write-Host ""

Write-Host "=== Tests Complete ===" -ForegroundColor Green
