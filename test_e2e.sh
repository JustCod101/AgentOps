#!/bin/sh
set -e

echo "=== 1. Testing Health Endpoint ==="
curl -s http://localhost:8080/actuator/health | grep '"status":"UP"'
echo "Health check passed."

echo -e "\n=== 2. Testing Diagnosis Stream Endpoint ==="
# Running a query in background or with a timeout to capture SSE
echo "Query: 最近10分钟数据库响应变慢，帮我排查"
curl -N -s --max-time 15 "http://localhost:8080/api/v1/diagnosis/stream?query=%E6%9C%80%E8%BF%9110%E5%88%86%E9%92%9F%E6%95%B0%E6%8D%AE%E5%BA%93%E5%93%8D%E5%BA%94%E5%8F%98%E6%85%A2%EF%BC%8C%E5%B8%AE%E6%88%91%E6%8E%92%E6%9F%A5" || true
echo -e "\nStream check finished."

echo -e "\n=== 3. Testing History Endpoint ==="
curl -s http://localhost:8080/api/v1/diagnosis/history
echo -e "\nHistory check finished."
