#!/usr/bin/env python3
"""
AgentOps 端到端集成测试
========================
测试运行中的 AgentOps Server 全部 API 功能:
  1. Health Check
  2. 知识库 CRUD + 搜索
  3. SSE 流式诊断（完整 Agent 协作流程）
  4. 诊断结果查询
  5. 轨迹回放 (trace + timeline)
  6. SQL 审计记录
  7. 历史列表分页

使用方法:
  1. 启动服务: cd agentops-server && docker compose up -d
  2. 运行测试: python3 e2e_test.py [--base-url http://localhost:8080] [--skip-diagnosis]

依赖: 仅使用 Python 标准库, 无需 pip install
"""

import urllib.request
import urllib.error
import json
import time
import sys
import argparse
import traceback
from typing import Optional

# ============================================================
# 配置
# ============================================================

BASE_URL = "http://localhost:8080"
DIAGNOSIS_TIMEOUT = 600  # SSE 诊断超时(秒)

# 统计
_passed = 0
_failed = 0
_skipped = 0


# ============================================================
# 工具函数
# ============================================================

def print_header(title: str):
    print(f"\n{'=' * 60}")
    print(f"  {title}")
    print(f"{'=' * 60}")


def print_sub(title: str):
    print(f"\n  --- {title} ---")


def ok(msg: str):
    global _passed
    _passed += 1
    print(f"  [PASS] {msg}")


def fail(msg: str):
    global _failed
    _failed += 1
    print(f"  [FAIL] {msg}")


def skip(msg: str):
    global _skipped
    _skipped += 1
    print(f"  [SKIP] {msg}")


def http_request(method: str, path: str, body=None, timeout=30) -> tuple:
    """发送 HTTP 请求, 返回 (status_code, response_body_dict)"""
    import urllib.parse
    # 只对 path 部分的 query string 进行 urlencode，这里简单处理用 quote
    parsed = urllib.parse.urlparse(path)
    encoded_path = urllib.parse.urlunparse((
        parsed.scheme,
        parsed.netloc,
        urllib.parse.quote(parsed.path),
        parsed.params,
        urllib.parse.quote(parsed.query, safe="=&"),
        parsed.fragment
    ))
    url = f"{BASE_URL}{encoded_path}"
    headers = {"Content-Type": "application/json"}
    data = json.dumps(body).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, {"_raw": raw}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8") if e.fp else ""
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw}
    except urllib.error.URLError as e:
        return 0, {"_error": str(e)}


def assert_eq(actual, expected, label: str):
    if actual == expected:
        ok(f"{label}: {actual}")
    else:
        fail(f"{label}: expected={expected}, actual={actual}")


def assert_true(condition: bool, label: str):
    if condition:
        ok(label)
    else:
        fail(label)


def assert_ok_response(status: int, body: dict, label: str) -> bool:
    """验证标准 Result<T> 响应格式: {code: 200, message: "success", data: ...}"""
    if status != 200:
        fail(f"{label}: HTTP {status}")
        return False
    if body.get("code") != 200:
        fail(f"{label}: code={body.get('code')}, message={body.get('message')}")
        return False
    ok(f"{label}: code=200")
    return True


# ============================================================
# 1. Health Check
# ============================================================

def test_health():
    print_header("1. Health Check")
    status, body = http_request("GET", "/actuator/health")
    assert_eq(status, 200, "HTTP status")
    assert_eq(body.get("status"), "UP", "application status")


# ============================================================
# 2. 知识库 CRUD + 搜索
# ============================================================

def test_knowledge_crud() -> Optional[int]:
    """测试知识库全部 API, 返回创建的知识条目 ID"""
    print_header("2. 知识库 API")
    created_id = None

    # 2.1 创建知识条目
    print_sub("2.1 POST /api/v1/knowledge - 创建")
    entry = {
        "category": "E2E_TEST",
        "title": "E2E 测试条目 - 连接池耗尽排障",
        "content": "当 HikariPool 连接池耗尽时, 需检查慢查询和连接泄漏",
        "tags": ["连接池", "HikariPool", "e2e-test"],
        "matchPatterns": ["connection pool", "HikariPool", "连接耗尽"],
        "priority": 99
    }
    status, body = http_request("POST", "/api/v1/knowledge", body=entry)
    if assert_ok_response(status, body, "创建知识条目"):
        data = body.get("data", {})
        created_id = data.get("id")
        assert_true(created_id is not None, f"返回 ID: {created_id}")
        assert_eq(data.get("category"), "E2E_TEST", "category")
        assert_eq(data.get("priority"), 99, "priority")

    # 2.2 按 ID 查询
    if created_id:
        print_sub("2.2 GET /api/v1/knowledge/{id} - 详情")
        status, body = http_request("GET", f"/api/v1/knowledge/{created_id}")
        if assert_ok_response(status, body, "查询知识条目"):
            assert_eq(body["data"]["title"], entry["title"], "title")

    # 2.3 列表查询 (按 category)
    print_sub("2.3 GET /api/v1/knowledge?category=E2E_TEST - 列表")
    status, body = http_request("GET", "/api/v1/knowledge?category=E2E_TEST")
    if assert_ok_response(status, body, "按分类查询"):
        assert_true(len(body["data"]) >= 1, f"返回 {len(body['data'])} 条")

    # 2.4 搜索 (关键词)
    print_sub("2.4 GET /api/v1/knowledge/search - 搜索")
    status, body = http_request("GET", "/api/v1/knowledge/search?keyword=HikariPool&limit=5")
    if assert_ok_response(status, body, "关键词搜索"):
        results = body.get("data", [])
        assert_true(len(results) >= 1, f"搜索到 {len(results)} 条")
        # 检查我们创建的条目在结果中
        ids_found = [r.get("id") for r in results]
        if created_id:
            assert_true(created_id in ids_found, f"搜索结果包含创建的条目 (id={created_id})")

    # 2.5 搜索 (category + keyword 组合)
    print_sub("2.5 GET /api/v1/knowledge/search - 分类+关键词组合搜索")
    status, body = http_request("GET",
                                 "/api/v1/knowledge/search?keyword=连接池&category=E2E_TEST&limit=3")
    if assert_ok_response(status, body, "组合搜索"):
        assert_true(len(body.get("data", [])) >= 1, "组合搜索有结果")

    # 2.6 更新
    if created_id:
        print_sub("2.6 PUT /api/v1/knowledge/{id} - 更新")
        update_body = {"title": "E2E 测试条目 - 已更新", "priority": 100}
        status, body = http_request("PUT", f"/api/v1/knowledge/{created_id}", body=update_body)
        if assert_ok_response(status, body, "更新知识条目"):
            assert_eq(body["data"]["title"], "E2E 测试条目 - 已更新", "更新后 title")
            assert_eq(body["data"]["priority"], 100, "更新后 priority")

    # 2.7 查询不存在的 ID
    print_sub("2.7 GET /api/v1/knowledge/999999 - 404")
    status, body = http_request("GET", "/api/v1/knowledge/999999")
    assert_eq(body.get("code"), 404, "不存在条目返回 404")

    # 2.8 列表查询 (全部, 不带 category)
    print_sub("2.8 GET /api/v1/knowledge - 全量列表")
    status, body = http_request("GET", "/api/v1/knowledge")
    if assert_ok_response(status, body, "全量列表"):
        total = len(body.get("data", []))
        assert_true(total >= 1, f"知识库共 {total} 条(含种子数据)")

    return created_id


def test_knowledge_delete(entry_id: Optional[int]):
    """清理测试数据"""
    if entry_id is None:
        return
    print_sub("2.9 DELETE /api/v1/knowledge/{id} - 删除测试数据")
    status, body = http_request("DELETE", f"/api/v1/knowledge/{entry_id}")
    assert_ok_response(status, body, "删除知识条目")
    # 验证删除后查不到
    status, body = http_request("GET", f"/api/v1/knowledge/{entry_id}")
    assert_eq(body.get("code"), 404, "删除后查询返回 404")


# ============================================================
# 3. SSE 流式诊断
# ============================================================

def test_diagnosis_stream() -> Optional[str]:
    """发起 SSE 流式诊断, 返回 sessionId"""
    print_header("3. SSE 流式诊断")

    url = f"{BASE_URL}/api/v1/diagnosis/stream"
    payload = json.dumps({"query": "最近10分钟数据库响应变慢，帮我排查原因"}).encode("utf-8")
    req = urllib.request.Request(url, data=payload,
                                 headers={"Content-Type": "application/json"},
                                 method="POST")

    session_id = None
    events = {"session": 0, "trace": 0, "result": 0, "done": 0, "error": 0}
    trace_agents = set()
    trace_types = set()
    result_data = None
    current_event = None

    try:
        with urllib.request.urlopen(req, timeout=DIAGNOSIS_TIMEOUT) as resp:
            print(f"  SSE 连接建立, 等待诊断完成 (超时: {DIAGNOSIS_TIMEOUT}s)...")
            print()

            for raw_line in resp:
                line = raw_line.decode("utf-8").strip()
                if not line:
                    continue

                if line.startswith("event:"):
                    current_event = line[6:].strip()
                elif line.startswith("data:"):
                    data_str = line[5:].strip()
                    try:
                        data = json.loads(data_str)
                    except json.JSONDecodeError:
                        continue

                    if current_event:
                        events[current_event] = events.get(current_event, 0) + 1

                    if current_event == "session":
                        session_id = data.get("sessionId")
                        print(f"    [session] sessionId={session_id}")

                    elif current_event == "trace":
                        agent = data.get("agentName", "?")
                        step_type = data.get("stepType", "?")
                        content = data.get("content", "")
                        trace_agents.add(agent)
                        trace_types.add(step_type)
                        # 截断过长内容
                        display = content[:80] + "..." if len(content) > 80 else content
                        print(f"    [trace] {agent}/{step_type}: {display}")

                    elif current_event == "result":
                        result_data = data
                        root_cause = data.get("rootCause", "N/A")
                        confidence = data.get("confidence", "N/A")
                        latency = data.get("totalLatencyMs", "N/A")
                        print(f"\n    [result] rootCause={root_cause}")
                        print(f"    [result] confidence={confidence}, latency={latency}ms")

                    elif current_event == "done":
                        done_status = data.get("status", "?")
                        print(f"\n    [done] status={done_status}")

                    elif current_event == "error":
                        error_msg = data.get("error", "unknown")
                        print(f"\n    [error] {error_msg}")

                    current_event = None

    except urllib.error.URLError as e:
        fail(f"SSE 连接失败: {e}")
        return None
    except Exception as e:
        fail(f"SSE 读取异常: {e}")
        return None

    # 验证 SSE 事件
    print()
    assert_true(session_id is not None, f"收到 session 事件 (sessionId={session_id})")
    assert_true(events["trace"] >= 5, f"收到 {events['trace']} 条 trace 事件 (>=5)")
    assert_true(events["result"] >= 1, f"收到 result 事件")

    # 验证参与的 Agent
    assert_true("ROUTER" in trace_agents, f"ROUTER Agent 参与")
    worker_agents = trace_agents - {"ROUTER"}
    assert_true(len(worker_agents) >= 1, f"Worker Agent 参与: {worker_agents}")

    # 验证 Trace 类型覆盖
    for t in ["THOUGHT", "ACTION", "OBSERVATION", "DECISION"]:
        assert_true(t in trace_types, f"Trace 类型包含 {t}")

    # 验证诊断结果
    if result_data:
        status = result_data.get("status")
        assert_true(status in ("COMPLETED", "FAILED"), f"诊断状态: {status}")
        if status == "COMPLETED":
            assert_true(result_data.get("rootCause") is not None, "有根因分析")
            assert_true(result_data.get("confidence") is not None, "有置信度")

    if events["error"] > 0:
        fail(f"收到 {events['error']} 个 error 事件")

    return session_id


# ============================================================
# 4. 诊断结果查询
# ============================================================

def test_get_session(session_id: str):
    print_header("4. 查询诊断结果")

    # 4.1 查询存在的 session
    print_sub(f"4.1 GET /api/v1/diagnosis/{session_id}")
    status, body = http_request("GET", f"/api/v1/diagnosis/{session_id}")
    if assert_ok_response(status, body, "查询诊断结果"):
        data = body.get("data", {})
        session = data.get("session", {})
        stats = data.get("traceStats", {})
        assert_true(session.get("sessionId") == session_id, f"sessionId 匹配")
        assert_true(session.get("status") in ("COMPLETED", "FAILED"), f"状态: {session.get('status')}")
        assert_true(session.get("intentType") is not None, f"意图类型: {session.get('intentType')}")
        assert_true(stats.get("totalSteps", 0) > 0, f"Trace 统计: {stats.get('totalSteps')} 步")
        if session.get("status") == "COMPLETED":
            assert_true(session.get("rootCause") is not None, "根因分析已持久化")
            assert_true(session.get("reportMarkdown") is not None, "报告 Markdown 已持久化")
            assert_true(session.get("completedAt") is not None, "completedAt 已设置")
            assert_true(session.get("totalLatencyMs", 0) > 0,
                        f"总耗时: {session.get('totalLatencyMs')}ms")

    # 4.2 查询不存在的 session
    print_sub("4.2 GET /api/v1/diagnosis/non-existent-id")
    status, body = http_request("GET", "/api/v1/diagnosis/non-existent-id-12345")
    assert_eq(body.get("code"), 404, "不存在的 session 返回 404")


# ============================================================
# 5. 轨迹回放
# ============================================================

def test_trace_and_timeline(session_id: str):
    print_header("5. 轨迹回放")

    # 5.1 完整 Trace
    print_sub(f"5.1 GET /api/v1/diagnosis/{session_id}/trace")
    status, body = http_request("GET", f"/api/v1/diagnosis/{session_id}/trace")
    if assert_ok_response(status, body, "获取完整 Trace"):
        traces = body.get("data", [])
        assert_true(len(traces) >= 5, f"Trace 条数: {len(traces)} (>=5)")
        if traces:
            # 验证 Trace 结构
            first = traces[0]
            assert_true("stepIndex" in first, "Trace 含 stepIndex")
            assert_true("agentName" in first, "Trace 含 agentName")
            assert_true("stepType" in first, "Trace 含 stepType")
            assert_true("content" in first, "Trace 含 content")
            # stepIndex 应该递增
            indices = [t.get("stepIndex", 0) for t in traces]
            assert_true(indices == sorted(indices), "stepIndex 递增排列")

    # 5.2 Timeline 格式
    print_sub(f"5.2 GET /api/v1/diagnosis/{session_id}/timeline")
    status, body = http_request("GET", f"/api/v1/diagnosis/{session_id}/timeline")
    if assert_ok_response(status, body, "获取 Timeline"):
        items = body.get("data", [])
        assert_true(len(items) >= 5, f"Timeline 条数: {len(items)} (>=5)")
        if items:
            first = items[0]
            assert_true("stepIndex" in first, "Timeline 含 stepIndex")
            assert_true("timestamp" in first, "Timeline 含 timestamp")

    # 5.3 不存在的 session
    print_sub("5.3 GET /api/v1/diagnosis/non-existent/trace - 404")
    status, body = http_request("GET", "/api/v1/diagnosis/non-existent-xyz/trace")
    assert_eq(body.get("code"), 404, "不存在 session 的 trace 返回 404")


# ============================================================
# 6. SQL 审计
# ============================================================

def test_sql_audit(session_id: str):
    print_header("6. SQL 审计记录")
    print_sub(f"6.1 GET /api/v1/diagnosis/{session_id}/sql-audit")
    status, body = http_request("GET", f"/api/v1/diagnosis/{session_id}/sql-audit")
    if assert_ok_response(status, body, "获取 SQL 审计记录"):
        audits = body.get("data", [])
        print(f"    SQL 审计记录数: {len(audits)}")
        if audits:
            first = audits[0]
            assert_true("originalSql" in first or "original_sql" in first,
                        "审计记录含原始 SQL")
            assert_true("isAllowed" in first or "is_allowed" in first,
                        "审计记录含 isAllowed")
            # 统计通过/拒绝
            allowed = sum(1 for a in audits
                          if a.get("isAllowed", a.get("is_allowed", False)))
            rejected = len(audits) - allowed
            ok(f"SQL 审计: {allowed} 条通过, {rejected} 条拒绝")
        else:
            skip("本次诊断未产生 SQL 审计记录 (可能 Text2SqlAgent 未被调度)")

    # 6.2 不存在的 session
    print_sub("6.2 GET /api/v1/diagnosis/non-existent/sql-audit - 404")
    status, body = http_request("GET", "/api/v1/diagnosis/non-existent-xyz/sql-audit")
    assert_eq(body.get("code"), 404, "不存在 session 的 sql-audit 返回 404")


# ============================================================
# 7. 历史列表
# ============================================================

def test_history(session_id: str):
    print_header("7. 历史诊断列表")

    # 7.1 默认分页
    print_sub("7.1 GET /api/v1/diagnosis/history - 默认分页")
    status, body = http_request("GET", "/api/v1/diagnosis/history")
    if assert_ok_response(status, body, "获取历史列表"):
        page_data = body.get("data", {})
        content = page_data.get("content", [])
        total = page_data.get("totalElements", 0)
        assert_true(total >= 1, f"总记录数: {total} (>=1)")
        assert_true(len(content) >= 1, f"当前页条数: {len(content)}")
        # 验证我们的 session 在里面
        session_ids = [s.get("sessionId") for s in content]
        assert_true(session_id in session_ids, "历史列表包含本次诊断 session")

    # 7.2 小分页
    print_sub("7.2 GET /api/v1/diagnosis/history?page=0&size=1 - 分页验证")
    status, body = http_request("GET", "/api/v1/diagnosis/history?page=0&size=1")
    if assert_ok_response(status, body, "分页查询"):
        page_data = body.get("data", {})
        content = page_data.get("content", [])
        assert_true(len(content) <= 1, f"size=1 返回 {len(content)} 条")

    # 7.3 大 size 被截断到 100
    print_sub("7.3 GET /api/v1/diagnosis/history?size=200 - size 上限")
    status, body = http_request("GET", "/api/v1/diagnosis/history?page=0&size=200")
    if assert_ok_response(status, body, "大 size 查询"):
        page_data = body.get("data", {})
        page_size = page_data.get("size", page_data.get("pageable", {}).get("pageSize", -1))
        assert_true(page_size <= 100, f"实际 pageSize={page_size} (<=100)")


# ============================================================
# 主流程
# ============================================================

def main():
    global BASE_URL, _passed, _failed, _skipped

    parser = argparse.ArgumentParser(description="AgentOps E2E 测试")
    parser.add_argument("--base-url", default=BASE_URL, help="服务地址")
    parser.add_argument("--skip-diagnosis", action="store_true",
                        help="跳过 SSE 诊断 (耗时长, 依赖 LLM)")
    args = parser.parse_args()
    BASE_URL = args.base_url

    print(f"\nAgentOps E2E 测试")
    print(f"服务地址: {BASE_URL}")
    print(f"跳过诊断: {args.skip_diagnosis}")

    start_time = time.time()

    try:
        # ====== 1. 健康检查 (前置条件) ======
        test_health()
        if _failed > 0:
            print("\n服务不可用, 终止测试。请确认服务已启动。")
            sys.exit(1)

        # ====== 2. 知识库 ======
        knowledge_id = test_knowledge_crud()

        # ====== 3~7. 诊断相关测试 ======
        session_id = None
        if args.skip_diagnosis:
            skip("跳过 SSE 流式诊断 (--skip-diagnosis)")
            # 尝试从历史中获取一个 session
            status, body = http_request("GET", "/api/v1/diagnosis/history?page=0&size=1")
            if status == 200 and body.get("code") == 200:
                content = body.get("data", {}).get("content", [])
                if content:
                    session_id = content[0].get("sessionId")
                    print(f"  使用历史 session: {session_id}")
        else:
            session_id = test_diagnosis_stream()

        if session_id:
            time.sleep(1)  # 等待数据完全落库
            test_get_session(session_id)
            test_trace_and_timeline(session_id)
            test_sql_audit(session_id)
            test_history(session_id)
        else:
            skip("无可用 session, 跳过诊断结果相关测试")

        # ====== 清理知识库测试数据 ======
        test_knowledge_delete(knowledge_id)

    except KeyboardInterrupt:
        print("\n\n测试被用户中断")
    except Exception as e:
        fail(f"未捕获异常: {e}")
        traceback.print_exc()

    # ====== 汇总 ======
    elapsed = time.time() - start_time
    total = _passed + _failed + _skipped

    print(f"\n{'=' * 60}")
    print(f"  测试完成  |  耗时: {elapsed:.1f}s")
    print(f"{'=' * 60}")
    print(f"  PASS:  {_passed}")
    print(f"  FAIL:  {_failed}")
    print(f"  SKIP:  {_skipped}")
    print(f"  TOTAL: {total}")
    print(f"{'=' * 60}")

    if _failed > 0:
        print(f"\n  结果: FAILED ({_failed} 个用例未通过)")
        sys.exit(1)
    else:
        print(f"\n  结果: ALL PASSED")
        sys.exit(0)


if __name__ == "__main__":
    main()
