import httpx
import pytest

from app.config import Settings
from app.model.deterministic import DeterministicModel
from app.model.factory import create_model
from app.model.openai_compatible import (
    ModelCallError,
    OpenAICompatibleModel,
    _safe_error_summary,
    normalize_base_url,
)


@pytest.mark.parametrize(
    ("value", "expected_base", "expected_endpoint"),
    [
        (
            "https://example.test",
            "https://example.test/v1",
            "https://example.test/v1/chat/completions",
        ),
        (
            "https://example.test/",
            "https://example.test/v1",
            "https://example.test/v1/chat/completions",
        ),
        (
            "https://example.test/v1/",
            "https://example.test/v1",
            "https://example.test/v1/chat/completions",
        ),
        (
            "https://example.test/v1/chat/completions",
            "https://example.test/v1",
            "https://example.test/v1/chat/completions",
        ),
        (
            "https://example.test/v1/chat/completions/",
            "https://example.test/v1",
            "https://example.test/v1/chat/completions",
        ),
    ],
)
def test_normalize_base_url(value, expected_base, expected_endpoint):
    assert normalize_base_url(value) == (expected_base, expected_endpoint)


@pytest.mark.parametrize(
    "value",
    ["", "example.test", "ftp://example.test", "https://example.test?a=1", "https://u:p@example.test"],
)
def test_normalize_base_url_rejects_invalid_values(value):
    with pytest.raises(ValueError):
        normalize_base_url(value)


def test_deterministic_model_answers_current_question_and_ignores_old_history():
    model = DeterministicModel("deterministic")
    prompt = "user: 旧问题\nassistant: AgentForge 已通过状态图处理\nuser: 你好"
    reply = model.build_reply(prompt)
    assert "你好" in reply
    assert "状态图处理" not in reply
    assert "tool_calls" not in reply


def test_deterministic_model_answers_platform_and_direct_questions():
    model = DeterministicModel("deterministic")
    assert "企业级 AI Agent 平台" in model.build_reply("请介绍 AgentForge 平台")
    assert model.build_reply("你好，请只回复：连接测试成功") == "连接测试成功"
    assert "2" in model.build_reply("请直接回答：1+1等于几？")


    bundle = create_model(Settings())
    assert bundle.provider == "deterministic"
    assert isinstance(bundle.model, DeterministicModel)


def test_factory_validates_provider_and_uses_settings_credentials():
    settings = Settings(model_provider="openai-compatible", model_base_url="https://example.test/", model_api_key=" secret ")
    bundle = create_model(settings)
    assert bundle.provider == "openai-compatible"
    assert isinstance(bundle.model, OpenAICompatibleModel)
    assert bundle.model.base_url == "https://example.test/v1"
    assert bundle.model.api_key == "secret"
    with pytest.raises(ValueError, match="不支持模型 provider"):
        create_model(Settings(), {"provider": "unknown"})


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (401, "上游模型认证失败"),
        (403, "上游模型访问被拒绝"),
        (404, "上游模型接口不存在"),
        (429, "上游模型请求过于频繁"),
        (500, "上游模型服务暂时不可用"),
        (503, "上游模型服务暂时不可用"),
    ],
)
def test_safe_error_summary_maps_http_status(status, expected):
    response = httpx.Response(status, request=httpx.Request("POST", "https://example.test"))
    assert _safe_error_summary(httpx.HTTPStatusError("failed", request=response.request, response=response)) == expected


def test_safe_error_summary_maps_network_errors():
    request = httpx.Request("POST", "https://example.test")
    assert _safe_error_summary(httpx.ReadTimeout("timeout", request=request)) == "请求上游模型超时"
    assert _safe_error_summary(httpx.ConnectError("connect", request=request)) == "无法连接上游模型"
    assert isinstance(ModelCallError("safe"), RuntimeError)
