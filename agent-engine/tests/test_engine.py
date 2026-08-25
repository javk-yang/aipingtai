from fastapi.testclient import TestClient

from app.main import app


def test_health() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "up"
    assert body["engine"] == "langgraph"


def test_chat_stream_contract() -> None:
    with TestClient(app).stream(
        "POST",
        "/v1/chat/stream",
        headers={"X-Trace-Id": "trace-test"},
        json={"prompt": "介绍 AgentForge", "conversation_id": "conv-test"},
    ) as response:
        events = [line for line in response.iter_lines() if line]
    assert response.status_code == 200
    assert any('"type":"message_start"' in line for line in events)
    assert any('"type":"content_delta"' in line for line in events)
    assert any('"type":"message_done"' in line for line in events)
    assert all('"trace_id":"trace-test"' in line for line in events)


def test_calculator_streams_tool_events() -> None:
    with TestClient(app).stream(
        "POST",
        "/v1/chat/stream",
        headers={"X-Trace-Id": "trace-tool"},
        json={"prompt": "帮我计算 12 * (3 + 4)", "conversation_id": "conv-tool"},
    ) as response:
        events = [line for line in response.iter_lines() if line]
    assert response.status_code == 200
    assert any('"type":"tool_call_start"' in line for line in events)
    assert any('"type":"tool_call_result"' in line for line in events)
    assert any('计算结果' in line for line in events)


def test_current_time_streams_tool_events() -> None:
    with TestClient(app).stream(
        "POST",
        "/v1/chat/stream",
        headers={"X-Trace-Id": "trace-time"},
        json={"prompt": "重庆现在几点？", "conversation_id": "conv-time"},
    ) as response:
        events = [line for line in response.iter_lines() if line]
    assert response.status_code == 200
    assert any('"tool_code":"get_current_time"' in line for line in events)
    assert any('"type":"tool_call_result"' in line for line in events)
