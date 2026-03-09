-- ============================================================
-- 监控库模拟数据 (monitor)
--
-- 故障场景: "数据库响应变慢"
-- 时间线:
--   T-60min ~ T-25min : 正常期
--   T-25min ~ T-15min : 恶化期（order_detail 表缺索引的SQL开始频繁出现）
--   T-15min ~ NOW     : 故障期（慢查询堆积 → 连接池打满 → 上游服务超时）
-- ============================================================

-- ============================================================
-- 1. 慢查询记录 (50条)
-- ============================================================

-- ===== 正常期: T-60min ~ T-25min，零星常规慢查询 =====
INSERT INTO slow_query_log (query_text, query_hash, execution_time_ms, rows_examined, rows_sent, lock_time_ms, db_name, user_host, created_at) VALUES
-- 常规报表查询，正常偏慢
('SELECT DATE(created_at), COUNT(*) FROM orders WHERE created_at > NOW() - INTERVAL ''7 days'' GROUP BY DATE(created_at)',
 'a1b2c3d4', 320, 85000, 7, 2, 'ecommerce', 'report-svc@10.0.1.20', NOW() - INTERVAL '58 minutes'),
('SELECT u.username, COUNT(o.id) FROM users u JOIN orders o ON u.id = o.user_id WHERE o.status = ''completed'' GROUP BY u.username ORDER BY COUNT(o.id) DESC LIMIT 20',
 'e5f6a7b8', 450, 120000, 20, 5, 'ecommerce', 'report-svc@10.0.1.20', NOW() - INTERVAL '55 minutes'),
('SELECT product_id, SUM(quantity) as total_qty FROM order_items WHERE created_at > NOW() - INTERVAL ''30 days'' GROUP BY product_id ORDER BY total_qty DESC LIMIT 50',
 'c9d0e1f2', 280, 65000, 50, 3, 'ecommerce', 'analytics-svc@10.0.1.25', NOW() - INTERVAL '50 minutes'),
('SELECT * FROM products WHERE category_id = 15 AND status = ''active'' ORDER BY updated_at DESC LIMIT 100',
 'f3a4b5c6', 150, 3200, 87, 1, 'ecommerce', 'product-svc@10.0.1.12', NOW() - INTERVAL '47 minutes'),
('SELECT COUNT(*) FROM user_sessions WHERE last_active_at > NOW() - INTERVAL ''30 minutes''',
 'd7e8f9a0', 180, 45000, 1, 0, 'ecommerce', 'auth-svc@10.0.1.10', NOW() - INTERVAL '42 minutes'),
('SELECT * FROM orders WHERE user_id = 8823 AND status IN (''pending'', ''processing'') ORDER BY created_at DESC',
 'b1c2d3e4', 105, 1500, 3, 1, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '38 minutes'),
('SELECT p.name, AVG(r.rating) FROM products p JOIN reviews r ON p.id = r.product_id GROUP BY p.id HAVING COUNT(r.id) > 10 ORDER BY AVG(r.rating) DESC LIMIT 20',
 'a5b6c7d8', 520, 180000, 20, 8, 'ecommerce', 'analytics-svc@10.0.1.25', NOW() - INTERVAL '35 minutes'),
('SELECT * FROM inventory WHERE quantity < reorder_level AND status = ''active''',
 'e9f0a1b2', 130, 8500, 42, 0, 'ecommerce', 'inventory-svc@10.0.1.18', NOW() - INTERVAL '30 minutes'),

-- ===== 恶化期开始: T-25min，问题 SQL 首次出现 =====
-- 核心问题SQL: order_detail 表按 order_id 查询但缺少索引，触发全表扫描
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290135',
 'PROBLEM_SQL_01', 2800, 4500000, 5, 12, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '25 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290130, 290131, 290132, 290133, 290134)',
 'PROBLEM_SQL_02', 3200, 4500000, 5, 8, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '24 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290140',
 'PROBLEM_SQL_01', 3500, 4500000, 4, 15, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '23 minutes'),
('SELECT * FROM orders WHERE user_id = 12055 AND created_at > NOW() - INTERVAL ''24 hours''',
 'b1c2d3e4', 125, 1800, 2, 1, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '22 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290148',
 'PROBLEM_SQL_01', 4100, 4500000, 6, 20, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '21 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290145, 290146, 290147, 290148)',
 'PROBLEM_SQL_02', 5200, 4500000, 4, 35, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '20 minutes'),

-- ===== 恶化加剧: T-20min ~ T-15min =====
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290155',
 'PROBLEM_SQL_01', 6800, 4500000, 3, 45, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '19 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290160',
 'PROBLEM_SQL_01', 7200, 4500000, 7, 60, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '18 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290155, 290156, 290157, 290158, 290159, 290160)',
 'PROBLEM_SQL_02', 8500, 4500000, 6, 80, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '17 minutes'),
('SELECT COUNT(*) FROM user_sessions WHERE last_active_at > NOW() - INTERVAL ''30 minutes''',
 'd7e8f9a0', 1200, 45000, 1, 5, 'ecommerce', 'auth-svc@10.0.1.10', NOW() - INTERVAL '17 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290165',
 'PROBLEM_SQL_01', 9500, 4500000, 5, 120, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '16 minutes'),
('SELECT * FROM products WHERE category_id = 8 AND status = ''active'' ORDER BY updated_at DESC LIMIT 50',
 'f3a4b5c6', 1800, 3200, 50, 200, 'ecommerce', 'product-svc@10.0.1.12', NOW() - INTERVAL '16 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290170',
 'PROBLEM_SQL_01', 11000, 4500000, 4, 180, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '15 minutes'),

-- ===== 故障期: T-15min ~ NOW，连接被占满，所有查询变慢 =====
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290175',
 'PROBLEM_SQL_01', 15000, 4500000, 5, 350, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '14 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290180',
 'PROBLEM_SQL_01', 18200, 4500000, 6, 500, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '13 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290175, 290176, 290177, 290178, 290179, 290180)',
 'PROBLEM_SQL_02', 16500, 4500000, 6, 420, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '13 minutes'),
-- 其他正常查询也开始变慢（连接等待）
('SELECT * FROM orders WHERE user_id = 15502 AND status IN (''pending'', ''processing'') ORDER BY created_at DESC',
 'b1c2d3e4', 5500, 1500, 2, 4800, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '12 minutes'),
('SELECT * FROM inventory WHERE product_id = 1024',
 'e9f0a1b2', 3200, 1, 1, 3100, 'ecommerce', 'inventory-svc@10.0.1.18', NOW() - INTERVAL '12 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290185',
 'PROBLEM_SQL_01', 22000, 4500000, 4, 800, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '11 minutes'),
('SELECT COUNT(*) FROM user_sessions WHERE last_active_at > NOW() - INTERVAL ''30 minutes''',
 'd7e8f9a0', 8000, 45000, 1, 7200, 'ecommerce', 'auth-svc@10.0.1.10', NOW() - INTERVAL '11 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290190',
 'PROBLEM_SQL_01', 25000, 4500000, 3, 1200, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '10 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290192',
 'PROBLEM_SQL_01', 28000, 4500000, 5, 1500, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '9 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290185, 290186, 290187, 290188, 290189, 290190)',
 'PROBLEM_SQL_02', 24000, 4500000, 6, 1100, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '9 minutes'),
('SELECT u.username, u.email FROM users u WHERE u.id = 15502',
 'simple_user', 4500, 1, 1, 4400, 'ecommerce', 'user-svc@10.0.1.11', NOW() - INTERVAL '8 minutes'),
('SELECT * FROM products WHERE id IN (1024, 1025, 1030) FOR UPDATE',
 'prod_lock', 12000, 3, 3, 11500, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '8 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290198',
 'PROBLEM_SQL_01', 30000, 4500000, 4, 2000, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '7 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290200',
 'PROBLEM_SQL_01', 28500, 4500000, 6, 1800, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '6 minutes'),
('SELECT * FROM orders WHERE status = ''processing'' AND created_at < NOW() - INTERVAL ''1 hour''',
 'stale_orders', 9500, 250000, 35, 8800, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '6 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290195, 290196, 290197, 290198, 290199, 290200)',
 'PROBLEM_SQL_02', 26000, 4500000, 6, 1600, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '5 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290205',
 'PROBLEM_SQL_01', 30000, 4500000, 5, 2200, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '4 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290208',
 'PROBLEM_SQL_01', 28000, 4500000, 3, 2500, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '3 minutes'),
('SELECT COUNT(*) FROM orders WHERE created_at > NOW() - INTERVAL ''1 hour''',
 'order_count', 6000, 250000, 1, 5500, 'ecommerce', 'dashboard-svc@10.0.1.30', NOW() - INTERVAL '3 minutes'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290210',
 'PROBLEM_SQL_01', 30000, 4500000, 7, 2800, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '2 minutes'),
('SELECT SUM(amount) as total, COUNT(*) as cnt FROM order_detail WHERE order_id IN (290205, 290206, 290207, 290208, 290209, 290210)',
 'PROBLEM_SQL_02', 27000, 4500000, 6, 2400, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '2 minutes'),
('SELECT * FROM inventory WHERE quantity < reorder_level AND status = ''active''',
 'e9f0a1b2', 7500, 8500, 42, 7000, 'ecommerce', 'inventory-svc@10.0.1.18', NOW() - INTERVAL '1 minute'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290215',
 'PROBLEM_SQL_01', 30000, 4500000, 4, 3000, 'ecommerce', 'order-svc@10.0.1.16', NOW() - INTERVAL '1 minute'),
('SELECT od.*, p.name, p.price FROM order_detail od JOIN products p ON od.product_id = p.id WHERE od.order_id = 290218',
 'PROBLEM_SQL_01', 29500, 4500000, 5, 3200, 'ecommerce', 'order-svc@10.0.1.15', NOW() - INTERVAL '30 seconds');


-- ============================================================
-- 2. 数据库连接状态 (24小时, 每5分钟一条 = 288条)
--    只展示关键时段的数据来体现故障演变
-- ============================================================

-- 生成正常期数据: T-24h ~ T-30min (使用 generate_series)
INSERT INTO db_connection_status (total_connections, active_connections, idle_connections, waiting_connections, max_connections, sampled_at)
SELECT
    15 + (random() * 8)::int,                                    -- total: 15~23
    5 + (random() * 6)::int,                                     -- active: 5~11
    8 + (random() * 5)::int,                                     -- idle: 8~13
    0,                                                            -- waiting: 0
    100,                                                          -- max: 100
    ts
FROM generate_series(
    NOW() - INTERVAL '24 hours',
    NOW() - INTERVAL '30 minutes',
    INTERVAL '5 minutes'
) AS ts;

-- T-25min: 开始异常，active 上升
INSERT INTO db_connection_status (total_connections, active_connections, idle_connections, waiting_connections, max_connections, sampled_at) VALUES
(32, 22, 10, 0,  100, NOW() - INTERVAL '25 minutes'),
(45, 35,  10, 0,  100, NOW() - INTERVAL '20 minutes'),
(62, 50,  12, 0,  100, NOW() - INTERVAL '15 minutes'),
-- 连接池开始紧张
(78, 65,  8,  5,  100, NOW() - INTERVAL '10 minutes'),
(88, 75,  5,  8,  100, NOW() - INTERVAL '5 minutes'),
-- 接近打满
(95, 82,  3,  10, 100, NOW() - INTERVAL '4 minutes'),
(98, 85,  2,  11, 100, NOW() - INTERVAL '3 minutes'),
(100, 88, 1,  11, 100, NOW() - INTERVAL '2 minutes'),
(100, 86, 0,  14, 100, NOW() - INTERVAL '1 minute');


-- ============================================================
-- 3. 微服务错误日志 (200条)
-- ============================================================

-- ===== 正常期零星日志: T-24h ~ T-25min (约30条) =====
INSERT INTO service_error_log (service_name, log_level, message, stack_trace, trace_id, span_id, host, created_at) VALUES
('user-service', 'WARN', 'Slow Redis response: 85ms for key user:session:8823', NULL, 'trace-0001', 'span-a1', 'user-svc-01', NOW() - INTERVAL '23 hours'),
('product-service', 'WARN', 'Cache miss rate above threshold: 12%', NULL, 'trace-0002', 'span-a2', 'prod-svc-01', NOW() - INTERVAL '20 hours'),
('payment-service', 'ERROR', 'Payment gateway timeout after 5000ms for order 289500', 'java.net.SocketTimeoutException: Read timed out\n\tat java.net.Socket.read(Socket.java:223)\n\tat com.agentops.payment.PaymentGatewayClient.charge(PaymentGatewayClient.java:89)', 'trace-0003', 'span-a3', 'pay-svc-01', NOW() - INTERVAL '18 hours'),
('order-service', 'WARN', 'Order processing took 1200ms, threshold is 1000ms', NULL, 'trace-0004', 'span-a4', 'order-svc-01', NOW() - INTERVAL '15 hours'),
('gateway', 'WARN', 'Rate limit approaching for IP 203.0.113.50: 450/500 req/min', NULL, 'trace-0005', 'span-a5', 'gw-01', NOW() - INTERVAL '12 hours'),
('user-service', 'ERROR', 'Failed to send verification email: SMTP connection refused', 'jakarta.mail.MessagingException: Could not connect to SMTP host\n\tat jakarta.mail.Transport.send(Transport.java:123)', 'trace-0006', 'span-a6', 'user-svc-02', NOW() - INTERVAL '10 hours'),
('inventory-service', 'WARN', 'Stock level critical for product 2048: 3 remaining', NULL, 'trace-0007', 'span-a7', 'inv-svc-01', NOW() - INTERVAL '8 hours'),
('product-service', 'ERROR', 'Elasticsearch cluster health: YELLOW, 1 unassigned shard', NULL, 'trace-0008', 'span-a8', 'prod-svc-01', NOW() - INTERVAL '6 hours'),
('order-service', 'WARN', 'Retry #2 for message publish to order.created topic', NULL, 'trace-0009', 'span-a9', 'order-svc-02', NOW() - INTERVAL '4 hours'),
('gateway', 'ERROR', 'Circuit breaker OPEN for payment-service, fallback activated', NULL, 'trace-0010', 'span-b0', 'gw-01', NOW() - INTERVAL '3 hours'),
('user-service', 'WARN', 'JWT token refresh rate spike: 200 req/min (normal: 50)', NULL, 'trace-0011', 'span-b1', 'user-svc-01', NOW() - INTERVAL '2 hours'),
('analytics-service', 'ERROR', 'Failed to flush metrics batch: buffer overflow (5000/5000)', 'java.lang.IllegalStateException: Buffer full\n\tat com.agentops.analytics.MetricBuffer.add(MetricBuffer.java:45)', 'trace-0012', 'span-b2', 'analytics-svc-01', NOW() - INTERVAL '90 minutes'),
('product-service', 'WARN', 'Image CDN response time degraded: avg 350ms (threshold 200ms)', NULL, 'trace-0013', 'span-b3', 'prod-svc-02', NOW() - INTERVAL '60 minutes'),
('order-service', 'WARN', 'Database query took 450ms: SELECT * FROM orders WHERE user_id = 9001', NULL, 'trace-0014', 'span-b4', 'order-svc-01', NOW() - INTERVAL '45 minutes');

-- ===== 恶化期: T-25min ~ T-15min, order-service 开始报错 (约40条) =====
INSERT INTO service_error_log (service_name, log_level, message, stack_trace, trace_id, span_id, host, created_at) VALUES
('order-service', 'WARN', 'Database query slow: 2800ms for order detail query, order_id=290135', NULL, 'trace-1001', 'span-c1', 'order-svc-01', NOW() - INTERVAL '25 minutes'),
('order-service', 'WARN', 'Database query slow: 3200ms for order detail aggregation', NULL, 'trace-1002', 'span-c2', 'order-svc-01', NOW() - INTERVAL '24 minutes'),
('order-service', 'WARN', 'Database query slow: 3500ms for order detail query, order_id=290140', NULL, 'trace-1003', 'span-c3', 'order-svc-02', NOW() - INTERVAL '23 minutes'),
('order-service', 'WARN', 'Request processing time exceeded threshold: 4200ms (limit: 3000ms)', NULL, 'trace-1004', 'span-c4', 'order-svc-01', NOW() - INTERVAL '22 minutes'),
('order-service', 'WARN', 'Database query slow: 4100ms for order detail query, order_id=290148', NULL, 'trace-1005', 'span-c5', 'order-svc-01', NOW() - INTERVAL '21 minutes'),
('order-service', 'WARN', 'Database query slow: 5200ms for order detail aggregation', NULL, 'trace-1006', 'span-c6', 'order-svc-02', NOW() - INTERVAL '20 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: POST /api/orders/290150/details', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.OrderDetailService.getDetails(OrderDetailService.java:67)\n\tat com.agentops.order.OrderController.getOrderDetails(OrderController.java:42)', 'trace-1007', 'span-c7', 'order-svc-01', NOW() - INTERVAL '19 minutes'),
('order-service', 'WARN', 'Database query slow: 6800ms for order detail query, order_id=290155', NULL, 'trace-1008', 'span-c8', 'order-svc-01', NOW() - INTERVAL '19 minutes'),
('gateway', 'WARN', 'Upstream order-service P99 latency: 5200ms (threshold: 2000ms)', NULL, 'trace-1009', 'span-c9', 'gw-01', NOW() - INTERVAL '18 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: GET /api/orders/290155/summary', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.OrderSummaryService.getSummary(OrderSummaryService.java:55)\n\tat com.agentops.order.OrderController.getOrderSummary(OrderController.java:58)', 'trace-1010', 'span-d0', 'order-svc-02', NOW() - INTERVAL '18 minutes'),
('order-service', 'WARN', 'Database query slow: 7200ms for order detail query, order_id=290160', NULL, 'trace-1011', 'span-d1', 'order-svc-02', NOW() - INTERVAL '18 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\n\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)', 'trace-1012', 'span-d2', 'order-svc-01', NOW() - INTERVAL '17 minutes'),
('order-service', 'WARN', 'Database query slow: 8500ms for order detail aggregation', NULL, 'trace-1013', 'span-d3', 'order-svc-01', NOW() - INTERVAL '17 minutes'),
('order-service', 'WARN', 'Active database connections: 35/50 (70%)', NULL, 'trace-1014', 'span-d4', 'order-svc-01', NOW() - INTERVAL '17 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: POST /api/orders/290162/checkout', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.CheckoutService.process(CheckoutService.java:78)\n\tat com.agentops.order.OrderController.checkout(OrderController.java:95)', 'trace-1015', 'span-d5', 'order-svc-02', NOW() - INTERVAL '16 minutes'),
('auth-service', 'WARN', 'Database query slow: 1200ms for session validation', NULL, 'trace-1016', 'span-d6', 'auth-svc-01', NOW() - INTERVAL '16 minutes'),
('order-service', 'WARN', 'Database query slow: 9500ms for order detail query, order_id=290165', NULL, 'trace-1017', 'span-d7', 'order-svc-01', NOW() - INTERVAL '16 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: GET /api/orders/290163/details', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.OrderDetailService.getDetails(OrderDetailService.java:67)', 'trace-1018', 'span-d8', 'order-svc-01', NOW() - INTERVAL '15 minutes'),
('product-service', 'WARN', 'Database query slow: 1800ms for product listing (normally 150ms)', NULL, 'trace-1019', 'span-d9', 'prod-svc-01', NOW() - INTERVAL '15 minutes'),
('order-service', 'WARN', 'Database query slow: 11000ms for order detail query, order_id=290170', NULL, 'trace-1020', 'span-e0', 'order-svc-02', NOW() - INTERVAL '15 minutes');

-- ===== 故障期: T-15min ~ NOW, 大量超时和连接池报错 (约120条) =====
INSERT INTO service_error_log (service_name, log_level, message, stack_trace, trace_id, span_id, host, created_at) VALUES
-- T-15min ~ T-12min
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2001', 'span-f1', 'order-svc-01', NOW() - INTERVAL '14 minutes 50 seconds'),
('order-service', 'ERROR', 'Request timeout after 5000ms: GET /api/orders/290175/details', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.OrderDetailService.getDetails(OrderDetailService.java:67)', 'trace-2002', 'span-f2', 'order-svc-01', NOW() - INTERVAL '14 minutes 30 seconds'),
('order-service', 'ERROR', 'Database query timeout: 15000ms for order detail query, order_id=290175', NULL, 'trace-2003', 'span-f3', 'order-svc-01', NOW() - INTERVAL '14 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2004', 'span-f4', 'order-svc-02', NOW() - INTERVAL '13 minutes 50 seconds'),
('order-service', 'ERROR', 'Database query timeout: 18200ms for order detail query, order_id=290180', NULL, 'trace-2005', 'span-f5', 'order-svc-02', NOW() - INTERVAL '13 minutes'),
('gateway', 'ERROR', 'Circuit breaker OPEN for order-service: failure rate 65% (threshold 50%)', NULL, 'trace-2006', 'span-f6', 'gw-01', NOW() - INTERVAL '13 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2007', 'span-f7', 'order-svc-01', NOW() - INTERVAL '12 minutes 30 seconds'),
('order-service', 'ERROR', 'Request timeout after 5000ms: POST /api/orders/290182/checkout', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.CheckoutService.process(CheckoutService.java:78)', 'trace-2008', 'span-f8', 'order-svc-01', NOW() - INTERVAL '12 minutes'),
('inventory-service', 'ERROR', 'Failed to update stock: database connection timeout', 'org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection\n\tat org.springframework.jdbc.support.JdbcUtils.closeConnection(JdbcUtils.java:89)', 'trace-2009', 'span-f9', 'inv-svc-01', NOW() - INTERVAL '12 minutes'),
('order-service', 'WARN', 'Active database connections: 50/50 (100%) - POOL EXHAUSTED', NULL, 'trace-2010', 'span-g0', 'order-svc-01', NOW() - INTERVAL '12 minutes'),
('payment-service', 'ERROR', 'Cannot process payment: order-service unavailable (circuit breaker OPEN)', NULL, 'trace-2011', 'span-g1', 'pay-svc-01', NOW() - INTERVAL '11 minutes 30 seconds'),

-- T-12min ~ T-9min: 连锁故障扩散
('order-service', 'ERROR', 'Database query timeout: 22000ms for order detail query, order_id=290185', NULL, 'trace-2012', 'span-g2', 'order-svc-01', NOW() - INTERVAL '11 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2013', 'span-g3', 'order-svc-01', NOW() - INTERVAL '11 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2014', 'span-g4', 'order-svc-02', NOW() - INTERVAL '10 minutes 45 seconds'),
('auth-service', 'ERROR', 'Database connection pool exhausted, cannot validate session', 'java.sql.SQLTransientConnectionException: HikariPool-2 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2015', 'span-g5', 'auth-svc-01', NOW() - INTERVAL '10 minutes 30 seconds'),
('order-service', 'ERROR', 'Request timeout after 5000ms: GET /api/orders/290188/status', 'java.util.concurrent.TimeoutException: Deadline exceeded', 'trace-2016', 'span-g6', 'order-svc-01', NOW() - INTERVAL '10 minutes'),
('order-service', 'ERROR', 'Database query timeout: 25000ms for order detail query, order_id=290190', NULL, 'trace-2017', 'span-g7', 'order-svc-02', NOW() - INTERVAL '10 minutes'),
('gateway', 'ERROR', 'High error rate detected: order-service 503 responses: 78% of requests', NULL, 'trace-2018', 'span-g8', 'gw-01', NOW() - INTERVAL '10 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2019', 'span-g9', 'order-svc-01', NOW() - INTERVAL '9 minutes 30 seconds'),
('order-service', 'ERROR', 'Database query timeout: 28000ms for order detail query, order_id=290192', NULL, 'trace-2020', 'span-h0', 'order-svc-01', NOW() - INTERVAL '9 minutes'),
('notification-service', 'ERROR', 'Failed to send order confirmation: order-service timeout', NULL, 'trace-2021', 'span-h1', 'notif-svc-01', NOW() - INTERVAL '9 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: POST /api/orders/290193/confirm', 'java.util.concurrent.TimeoutException: Deadline exceeded', 'trace-2022', 'span-h2', 'order-svc-02', NOW() - INTERVAL '8 minutes 30 seconds'),

-- T-9min ~ T-6min
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2023', 'span-h3', 'order-svc-01', NOW() - INTERVAL '8 minutes'),
('user-service', 'ERROR', 'Failed to load user profile: database timeout', 'org.springframework.dao.QueryTimeoutException: JDBC query timeout\n\tat org.springframework.orm.jpa.JpaSystemException.convert(JpaSystemException.java:56)', 'trace-2024', 'span-h4', 'user-svc-01', NOW() - INTERVAL '8 minutes'),
('order-service', 'ERROR', 'Database query timeout: 30000ms for order detail query, order_id=290198', NULL, 'trace-2025', 'span-h5', 'order-svc-02', NOW() - INTERVAL '7 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2026', 'span-h6', 'order-svc-01', NOW() - INTERVAL '7 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: GET /api/orders/290199/details', 'java.util.concurrent.TimeoutException: Deadline exceeded', 'trace-2027', 'span-h7', 'order-svc-01', NOW() - INTERVAL '6 minutes 30 seconds'),
('gateway', 'ERROR', 'order-service health check FAILED: 3 consecutive failures', NULL, 'trace-2028', 'span-h8', 'gw-01', NOW() - INTERVAL '6 minutes'),
('order-service', 'ERROR', 'Database query timeout: 28500ms for order detail query, order_id=290200', NULL, 'trace-2029', 'span-h9', 'order-svc-01', NOW() - INTERVAL '6 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2030', 'span-i0', 'order-svc-02', NOW() - INTERVAL '6 minutes'),

-- T-6min ~ T-3min
('order-service', 'ERROR', 'Request timeout after 5000ms: POST /api/orders/create', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.OrderService.createOrder(OrderService.java:45)', 'trace-2031', 'span-i1', 'order-svc-01', NOW() - INTERVAL '5 minutes 30 seconds'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2032', 'span-i2', 'order-svc-01', NOW() - INTERVAL '5 minutes'),
('payment-service', 'ERROR', 'Order validation failed: cannot reach order-service', 'java.net.ConnectException: Connection refused\n\tat com.agentops.payment.OrderClient.validateOrder(OrderClient.java:34)', 'trace-2033', 'span-i3', 'pay-svc-01', NOW() - INTERVAL '5 minutes'),
('order-service', 'ERROR', 'Database query timeout: 30000ms for order detail query, order_id=290205', NULL, 'trace-2034', 'span-i4', 'order-svc-01', NOW() - INTERVAL '4 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2035', 'span-i5', 'order-svc-02', NOW() - INTERVAL '4 minutes'),
('inventory-service', 'ERROR', 'Stock reservation timeout: cannot acquire DB connection for product 1030', 'java.sql.SQLTransientConnectionException: HikariPool-3 - Connection is not available', 'trace-2036', 'span-i6', 'inv-svc-01', NOW() - INTERVAL '3 minutes 30 seconds'),
('order-service', 'ERROR', 'Request timeout after 5000ms: GET /api/orders/290207/track', 'java.util.concurrent.TimeoutException: Deadline exceeded', 'trace-2037', 'span-i7', 'order-svc-01', NOW() - INTERVAL '3 minutes'),
('order-service', 'ERROR', 'Database query timeout: 28000ms for order detail query, order_id=290208', NULL, 'trace-2038', 'span-i8', 'order-svc-02', NOW() - INTERVAL '3 minutes'),
('gateway', 'ERROR', 'Circuit breaker OPEN for order-service: failure rate 85% (threshold 50%)', NULL, 'trace-2039', 'span-i9', 'gw-01', NOW() - INTERVAL '3 minutes'),

-- T-3min ~ NOW
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2040', 'span-j0', 'order-svc-01', NOW() - INTERVAL '2 minutes 50 seconds'),
('order-service', 'ERROR', 'Database query timeout: 30000ms for order detail query, order_id=290210', NULL, 'trace-2041', 'span-j1', 'order-svc-01', NOW() - INTERVAL '2 minutes'),
('order-service', 'ERROR', 'Request timeout after 5000ms: POST /api/orders/290211/checkout', 'java.util.concurrent.TimeoutException: Deadline exceeded\n\tat com.agentops.order.CheckoutService.process(CheckoutService.java:78)', 'trace-2042', 'span-j2', 'order-svc-02', NOW() - INTERVAL '2 minutes'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2043', 'span-j3', 'order-svc-01', NOW() - INTERVAL '1 minute 30 seconds'),
('payment-service', 'ERROR', 'Payment processing failed: upstream order-service unavailable', NULL, 'trace-2044', 'span-j4', 'pay-svc-01', NOW() - INTERVAL '1 minute'),
('order-service', 'ERROR', 'Database query timeout: 29500ms for order detail query, order_id=290218', NULL, 'trace-2045', 'span-j5', 'order-svc-01', NOW() - INTERVAL '30 seconds'),
('order-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 3000ms', 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)', 'trace-2046', 'span-j6', 'order-svc-02', NOW() - INTERVAL '15 seconds');

-- 补充更多 order-service 错误日志填充到 ~200 条
INSERT INTO service_error_log (service_name, log_level, message, stack_trace, trace_id, span_id, host, created_at)
SELECT
    CASE WHEN random() < 0.85 THEN 'order-service'
         WHEN random() < 0.92 THEN 'inventory-service'
         WHEN random() < 0.96 THEN 'payment-service'
         ELSE 'user-service'
    END,
    CASE WHEN random() < 0.7 THEN 'ERROR' ELSE 'WARN' END,
    CASE
        WHEN random() < 0.4 THEN 'HikariPool-1 - Connection is not available, request timed out after 3000ms'
        WHEN random() < 0.65 THEN 'Request timeout after 5000ms: order detail query'
        WHEN random() < 0.8 THEN 'Database query timeout: ' || (15000 + (random() * 15000)::int) || 'ms'
        WHEN random() < 0.9 THEN 'Failed to acquire JDBC connection within 3000ms'
        ELSE 'Downstream service unavailable: order-service circuit breaker OPEN'
    END,
    CASE WHEN random() < 0.5
        THEN 'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\n\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)'
        ELSE NULL
    END,
    'trace-gen-' || gs,
    'span-gen-' || gs,
    CASE WHEN random() < 0.5 THEN 'order-svc-01' ELSE 'order-svc-02' END,
    NOW() - (random() * 14)::int * INTERVAL '1 minute'
FROM generate_series(1, 80) AS gs;


-- ============================================================
-- 4. 系统指标 (近1小时, 每分钟采样)
--    体现故障期 CPU/内存/QPS/P99 的异常变化
-- ============================================================

-- CPU Usage (%) — order-service: 正常 15~25%, 故障期飙到 85%+
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'cpu_usage',
    'order-service',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 70 + (random() * 25)     -- 70~95%
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 30 + (random() * 30)     -- 30~60%
        ELSE 12 + (random() * 15)                                              -- 12~27%
    END,
    '{"instance": "order-svc-01", "unit": "percent"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- CPU Usage — database server
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'cpu_usage',
    'postgres-primary',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 75 + (random() * 20)     -- 75~95%
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 35 + (random() * 25)     -- 35~60%
        ELSE 10 + (random() * 12)                                              -- 10~22%
    END,
    '{"instance": "db-primary-01", "unit": "percent"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- Memory Usage (%) — order-service: 正常 40~55%, 故障期 75~90%
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'memory_usage',
    'order-service',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 75 + (random() * 15)
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 55 + (random() * 15)
        ELSE 40 + (random() * 15)
    END,
    '{"instance": "order-svc-01", "unit": "percent"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- Memory Usage — database server
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'memory_usage',
    'postgres-primary',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 72 + (random() * 15)
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 50 + (random() * 15)
        ELSE 35 + (random() * 12)
    END,
    '{"instance": "db-primary-01", "unit": "percent"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- QPS — order-service: 正常 ~200, 故障期下降到 30~60 (请求被拒/超时)
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'qps',
    'order-service',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 25 + (random() * 40)     -- 25~65
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 120 + (random() * 80)    -- 120~200
        ELSE 180 + (random() * 50)                                             -- 180~230
    END,
    '{"instance": "order-svc-01", "unit": "req/s"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- P99 Latency (ms) — order-service: 正常 50~120ms, 故障期 8000~30000ms
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'latency_p99',
    'order-service',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 8000 + (random() * 22000)   -- 8~30s
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 800 + (random() * 4000)      -- 0.8~4.8s
        ELSE 50 + (random() * 70)                                                  -- 50~120ms
    END,
    '{"instance": "order-svc-01", "unit": "ms", "quantile": "0.99"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- P99 Latency — postgres
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'latency_p99',
    'postgres-primary',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 5000 + (random() * 25000)
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 500 + (random() * 3000)
        ELSE 10 + (random() * 40)
    END,
    '{"instance": "db-primary-01", "unit": "ms", "quantile": "0.99"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- DB Active Connections 指标
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'db_active_connections',
    'postgres-primary',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 75 + (random() * 15)
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 25 + (random() * 30)
        ELSE 5 + (random() * 8)
    END,
    '{"instance": "db-primary-01", "max": 100}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;

-- Disk IO Wait (%) — database server
INSERT INTO system_metric (metric_name, service_name, value, labels, sampled_at)
SELECT
    'disk_io_wait',
    'postgres-primary',
    CASE
        WHEN ts > NOW() - INTERVAL '15 minutes' THEN 40 + (random() * 35)     -- 40~75%
        WHEN ts > NOW() - INTERVAL '25 minutes' THEN 10 + (random() * 20)     -- 10~30%
        ELSE 2 + (random() * 5)                                                -- 2~7%
    END,
    '{"instance": "db-primary-01", "unit": "percent"}'::jsonb,
    ts
FROM generate_series(NOW() - INTERVAL '60 minutes', NOW(), INTERVAL '1 minute') AS ts;
