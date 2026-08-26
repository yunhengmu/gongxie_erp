"""API 集成测试。"""
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_ready():
    resp = client.get("/ready")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ready"}


def test_chat_endpoint():
    resp = client.post("/v1/chat", json={
        "messages": [{"role": "user", "content": "hello"}],
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "thread_id" in data
    assert "response" in data


def test_chat_with_thread():
    """同一 thread_id 应保持上下文。"""
    thread_id = "test-thread-001"
    resp = client.post("/v1/chat", json={
        "messages": [{"role": "user", "content": "我叫小明"}],
        "thread_id": thread_id,
    })
    assert resp.status_code == 200

    # 第二轮应能记住名字
    resp2 = client.post("/v1/chat", json={
        "messages": [{"role": "user", "content": "我叫什么？"}],
        "thread_id": thread_id,
    })
    assert resp2.status_code == 200


def test_memory_same_thread():
    """场景九：同一 thread_id 下对话记忆验证。"""
    tid = "memory-test-01"

    # 步骤1：告诉系统个人信息
    resp1 = client.post("/v1/chat", json={
        "thread_id": tid,
        "messages": [{"role": "user", "content": "我叫张三，是一名后端工程师"}],
    })
    assert resp1.status_code == 200
    data1 = resp1.json()
    assert data1["thread_id"] == tid
    print(f"[步骤1] response: {data1['response']}")

    # 步骤2：同一 thread_id 追问，验证能记住
    resp2 = client.post("/v1/chat", json={
        "thread_id": tid,
        "messages": [{"role": "user", "content": "我叫什么名字？我的职业是什么？"}],
    })
    assert resp2.status_code == 200
    data2 = resp2.json()
    assert data2["thread_id"] == tid
    response2 = data2["response"]
    print(f"[步骤2] response: {response2}")

    # 验证回答中包含姓名和职业
    assert "张三" in response2, f"期望回答包含'张三'，实际: {response2}"
    assert "后端" in response2 or "工程师" in response2, \
        f"期望回答包含职业信息，实际: {response2}"


def test_memory_isolated_thread():
    """场景九：切换 thread_id 验证记忆隔离。"""
    # 步骤3：新 thread_id 追问，不应记住之前的信息
    resp3 = client.post("/v1/chat", json={
        "thread_id": "memory-test-02",
        "messages": [{"role": "user", "content": "我叫什么名字？"}],
    })
    assert resp3.status_code == 200
    data3 = resp3.json()
    response3 = data3["response"]
    print(f"[步骤3] response: {response3}")

    # 验证不会串记忆——不应出现"张三"
    assert "张三" not in response3, \
        f"记忆隔离失败！新 thread 不应知道'张三'，实际: {response3}"