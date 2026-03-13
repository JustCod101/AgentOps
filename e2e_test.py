import urllib.request
import urllib.error
import json
import time
import sys

BASE_URL = "http://localhost:8080"

def print_header(title):
    print(f"\n{'='*50}\n{title}\n{'='*50}")

def test_health():
    print_header("1. 测试 Health Check (/actuator/health)")
    url = f"{BASE_URL}/actuator/health"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode())
        if data.get("status") == "UP":
            print("✅ 健康检查通过")
        else:
            print(f"❌ 健康检查失败: {data}")

def test_diagnosis_stream():
    print_header("2. 测试流式诊断 API (/api/v1/diagnosis/stream)")
    url = f"{BASE_URL}/api/v1/diagnosis/stream"
    payload = json.dumps({"query": "最近10分钟数据库响应变慢，帮我排查"}).encode('utf-8')
    headers = {'Content-Type': 'application/json'}
    
    event_name = None
    success = False
    req = urllib.request.Request(url, data=payload, headers=headers, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            print("⏳ 正在接收 SSE 流 (预计需要 10-60 秒，取决于 LLM 响应速度)...\n")
            for line in response:
                line = line.decode('utf-8').strip()
                if line.startswith('event:'):
                    event_name = line[6:].strip()
                elif line.startswith('data:'):
                    data_str = line[5:].strip()
                    try:
                        data = json.loads(data_str)
                        if event_name == 'report':
                            print("\n✅ 接收到最终报告!")
                            success = True
                        else:
                            agent = data.get('agent', 'SYSTEM')
                            content = data.get('content', '')
                            # 对于过长的内容（如观察结果）做截断显示
                            if len(content) > 100:
                                content = content[:100] + "..."
                            print(f"[{event_name.upper()}] {agent}: {content}")
                    except json.JSONDecodeError:
                        pass
    except urllib.error.URLError as e:
        print(f"❌ SSE 请求异常: {e}")
        
    return success

def get_latest_session_id():
    url = f"{BASE_URL}/api/v1/diagnosis/history?page=0&size=1"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode())
        content = data.get("content", [])
        if content:
            return content[0].get("sessionId")
    return None

def test_get_session(session_id):
    print_header(f"3. 测试查询会话详情 (/api/v1/diagnosis/{session_id})")
    url = f"{BASE_URL}/api/v1/diagnosis/{session_id}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode())
        print(f"✅ 获取成功，当前状态: {data.get('status')}")

def test_get_trace(session_id):
    print_header(f"4. 测试查询排障轨迹 (/api/v1/diagnosis/{session_id}/trace)")
    url = f"{BASE_URL}/api/v1/diagnosis/{session_id}/trace"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode())
            print(f"✅ 获取成功，共 {len(data)} 条轨迹记录")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("⚠️ 获取失败 (404), 可能 /trace 端点已被重命名为 /timeline 或未实现")
        else:
            print(f"❌ 请求失败: {e}")

def main():
    try:
        test_health()
        stream_ok = test_diagnosis_stream()
        if not stream_ok:
            print("⚠️ 流式诊断未正常完成，这可能是因为 LLM API 超时或报错。")
            
        time.sleep(2) # 等待数据落库
        
        session_id = get_latest_session_id()
        if session_id:
            print(f"\n获取到最新 Session ID: {session_id}")
            test_get_session(session_id)
            test_get_trace(session_id)
        else:
            print("⚠️ 未能获取到历史 Session ID，可能是因为历史记录为空。")
            
    except Exception as e:
        print(f"❌ 测试过程中发生异常: {e}")

if __name__ == '__main__':
    main()
