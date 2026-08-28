"""OpenAI-compatible 真实大模型适配器。

与 DeterministicModel 保持同一套方法签名（model_name / build_reply /
plan_tool / build_tool_reply / astream），从而可在不改动 LangGraph 图结构
的前提下，由请求级传入的 model_config 动态切换为真实 LLM。

支持任意 OpenAI 兼容端点（/v1/chat/completions）：OpenAI、DeepSeek、
通义千问、本地 Ollama（base_url=http://host:11434/v1）等。
"""

from __future__ import annotations

import json
import re
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import SplitResult, urlsplit, urlunsplit

import httpx


SUPPORTED_PROVIDERS = frozenset({
    "openai",
    "openai-compatible",
    "deepseek",
    "qwen",
    "ollama",
})
CHAT_COMPLETIONS_SUFFIX = "/chat/completions"


class ModelCallError(RuntimeError):
    """真实模型调用失败，消息只包含可安全对外暴露的摘要。"""


def normalize_provider(provider: Any) -> str:
    if not isinstance(provider, str):
        raise ValueError("模型 provider 必须是字符串")
    value = provider.strip().lower()
    if not value:
        raise ValueError("缺少模型 provider")
    if value == "deterministic":
        return value
    if value not in SUPPORTED_PROVIDERS:
        raise ValueError(f"不支持模型 provider={value!r}")
    return value


def _rebuild_url(parts: SplitResult, path: str) -> str:
    return urlunsplit((parts.scheme, parts.netloc, path, "", ""))


def normalize_base_url(base_url: Any) -> tuple[str, str]:
    """校验并规范化兼容端点，返回 (base_url, chat_completions_url)。"""
    if not isinstance(base_url, str):
        raise ValueError("模型 base_url 必须是字符串")
    raw = base_url.strip()
    if not raw:
        raise ValueError("缺少模型 base_url")
    parts = urlsplit(raw)
    if parts.scheme.lower() not in {"http", "https"} or not parts.netloc:
        raise ValueError("模型 base_url 必须是 http(s) URL")
    if parts.username is not None or parts.password is not None:
        raise ValueError("模型 base_url 不允许包含用户凭据")
    if parts.query or parts.fragment:
        raise ValueError("模型 base_url 不允许包含 query 或 fragment")

    path = parts.path.rstrip("/")
    lower_path = path.lower()
    if lower_path.endswith(CHAT_COMPLETIONS_SUFFIX):
        # 已经填写完整端点时保持其路径，避免重复拼接。
        endpoint_path = path
        base_path = path[: -len(CHAT_COMPLETIONS_SUFFIX)].rstrip("/")
        return _rebuild_url(parts, base_path), _rebuild_url(parts, endpoint_path)

    # 只填写 host 时默认补上 OpenAI 兼容 API 的 /v1。
    base_path = path or "/v1"
    base_url = _rebuild_url(parts, base_path)
    return base_url, f"{base_url}{CHAT_COMPLETIONS_SUFFIX}"


def _safe_error_summary(exc: Exception) -> str:
    if isinstance(exc, httpx.TimeoutException):
        return "请求上游模型超时"
    if isinstance(exc, httpx.ConnectError):
        return "无法连接上游模型"
    if isinstance(exc, httpx.HTTPStatusError):
        status = exc.response.status_code
        try:
            body = exc.response.json() or {}
            upstream_msg = body.get("error", {}).get("message") or body.get("message") or ""
        except Exception:
            upstream_msg = ""
        if status == 401:
            return "上游模型认证失败"
        if status == 402:
            if "Insufficient Balance" in upstream_msg or "余额" in upstream_msg:
                return "上游模型余额不足，请充值或更换 API Key"
            return "上游模型需要付费或余额不足"
        if status == 403:
            return "上游模型访问被拒绝"
        if status == 404:
            return "上游模型接口不存在"
        if status == 429:
            return "上游模型请求过于频繁"
        if 500 <= status <= 599:
            return "上游模型服务暂时不可用"
        return f"上游模型返回 HTTP {status}"
    if isinstance(exc, httpx.RequestError):
        return "上游模型网络请求失败"
    if isinstance(exc, (KeyError, TypeError, ValueError, json.JSONDecodeError)):
        return "上游模型响应格式无效"
    return "上游模型调用失败"


def _model_call_error(operation: str, exc: Exception) -> ModelCallError:
    return ModelCallError(f"{operation}：{_safe_error_summary(exc)}")


class OpenAICompatibleModel:
    """请求级真实大模型：由 model_config（provider/model/base_url/api_key）驱动。"""

    def __init__(
        self,
        model_name: str,
        base_url: str = "https://api.openai.com/v1",
        api_key: str = "",
        temperature: float = 0.7,
        max_tokens: int = 1024,
    ) -> None:
        if not isinstance(model_name, str) or not model_name.strip():
            raise ValueError("缺少模型 model")
        if not isinstance(api_key, str):
            raise ValueError("模型 api_key 必须是字符串")
        normalized_base_url, endpoint_url = normalize_base_url(base_url)
        self.model_name = model_name.strip()
        self.base_url = normalized_base_url
        self.chat_completions_url = endpoint_url
        self.api_key = api_key.strip()
        self.temperature = temperature
        self.max_tokens = max_tokens

    # ------------------------------------------------------------------
    # 内部：调用兼容端点
    # ------------------------------------------------------------------
    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def _chat(self, messages: list[dict[str, str]], tools: list[dict] | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.model_name,
            "messages": messages,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
            "stream": False,
        }
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"
        # trust_env=False：忽略 HTTP_PROXY/HTTPS_PROXY 等环境变量，
        # 避免在沙箱/IDE 内被透明代理带偏导致连接失败。
        # connect=10s：上游不可达时快速失败，避免长时间挂起。
        with httpx.Client(
            timeout=httpx.Timeout(90.0, connect=10.0),
            trust_env=False,
        ) as client:
            resp = client.post(
                self.chat_completions_url,
                json=payload,
                headers=self._headers(),
            )
            resp.raise_for_status()
            return resp.json()

    @staticmethod
    def _parse_tool_arguments(raw: Any) -> dict[str, Any]:
        if isinstance(raw, dict):
            return raw
        if isinstance(raw, str):
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                return {}
            return parsed if isinstance(parsed, dict) else {}
        return {}

    @staticmethod
    def _message(data: dict[str, Any]) -> dict[str, Any]:
        choices = data.get("choices") or []
        if not choices or not isinstance(choices[0], dict):
            return {}
        message = choices[0].get("message") or {}
        return message if isinstance(message, dict) else {}

    def _extract_tool_plan(
        self,
        data: dict[str, Any],
        tools: list[dict[str, Any]],
    ) -> dict[str, Any] | None:
        """兼容不同 OpenAI-compatible 端点的工具规划返回格式。

        有些模型不会遵守“只输出 tool_code JSON”的提示，而是返回：
        {"tool_calls":[{"name":"...","arguments":{...}}]}，
        或使用标准 message.tool_calls 结构。两种格式都必须在引擎内部
        转成统一的 tool_code，不能把原始 JSON 直接展示给用户。
        """
        available = {
            str(item.get("code")): item
            for item in tools
            if item.get("code")
        }
        message = self._message(data)
        candidates: list[dict[str, Any]] = []

        structured = message.get("tool_calls")
        if isinstance(structured, list):
            for item in structured:
                if not isinstance(item, dict):
                    continue
                function = item.get("function")
                if isinstance(function, dict):
                    candidates.append(function)
                else:
                    candidates.append(item)

        content = message.get("content")
        if isinstance(content, str) and content.strip():
            try:
                parsed = json.loads(content)
            except json.JSONDecodeError:
                parsed = None
            if isinstance(parsed, dict):
                direct_code = parsed.get("tool_code")
                if direct_code:
                    candidates.append({
                        "name": direct_code,
                        "arguments": parsed.get("arguments", {}),
                    })
                raw_calls = parsed.get("tool_calls")
                if isinstance(raw_calls, list):
                    for item in raw_calls:
                        if isinstance(item, dict):
                            function = item.get("function")
                            candidates.append(function if isinstance(function, dict) else item)

        for candidate in candidates:
            name = str(candidate.get("name") or candidate.get("tool_code") or "").strip()
            if not name:
                continue
            # 优先精确匹配；部分兼容端点会返回展示名，因此再按名称匹配。
            code = name if name in available else next(
                (
                    item_code
                    for item_code, descriptor in available.items()
                    if name == str(descriptor.get("name") or "")
                ),
                None,
            )
            if code is not None:
                return {
                    "tool_code": code,
                    "arguments": self._parse_tool_arguments(candidate.get("arguments")),
                }
        return None

    @staticmethod
    def _is_raw_tool_call_content(content: Any) -> bool:
        if not isinstance(content, str):
            return False
        text = content.strip()
        if not text or not text.startswith("{"):
            return False
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            return False
        return isinstance(parsed, dict) and (
            isinstance(parsed.get("tool_calls"), list)
            or bool(parsed.get("tool_code"))
        )

    def _safe_natural_reply(self, prompt: str, content: Any) -> str:
        """防止上游把内部工具规划 JSON 作为最终答案展示给用户。"""
        if not self._is_raw_tool_call_content(content):
            return str(content or "")
        retry_messages = [
            {
                "role": "system",
                "content": (
                    "请直接用简洁、自然的中文回答用户。不要调用工具，"
                    "不要输出 JSON、tool_calls、函数名或内部协议。"
                ),
            },
            {"role": "user", "content": prompt},
        ]
        try:
            retry_data = self._chat(retry_messages)
            retry_content = self._message(retry_data).get("content")
            if retry_content and not self._is_raw_tool_call_content(retry_content):
                return str(retry_content)
        except Exception:
            pass
        return "你好！我是 AgentForge 智能助手，很高兴为你服务。"

    # ------------------------------------------------------------------
    # 与 DeterministicModel 对齐的接口
    # ------------------------------------------------------------------
    def build_reply(self, prompt: str, skill: dict[str, Any] | None = None) -> str:
        """普通对话：直接调用真实 LLM 生成回复。"""
        messages = [{"role": "user", "content": prompt}]
        if skill:
            name = str(skill.get("name", ""))
            markdown = str(skill.get("markdown", ""))
            messages.insert(
                0,
                {
                    "role": "system",
                    "content": f"你是 AgentForge 平台助手，正在按技能「{name}」处理请求。"
                    f"技能指令如下：\n{markdown}",
                },
            )
        try:
            data = self._chat(messages)
            content = self._message(data).get("content")
            return self._safe_natural_reply(prompt, content)
        except Exception as exc:
            raise _model_call_error("生成回复失败", exc) from exc

    def build_reply_with_reasoning(self, prompt: str, skill: dict[str, Any] | None = None) -> dict[str, Any]:
        """普通对话：调用真实 LLM，返回 {content, reasoning}。

        reasoning 为模型真实思维链（reasoning_content / reasoning / thinking 等字段），
        无思维链能力时返回空串。用于前端"显示隐藏思维链"。
        """
        messages = [{"role": "user", "content": prompt}]
        if skill:
            name = str(skill.get("name", ""))
            markdown = str(skill.get("markdown", ""))
            messages.insert(
                0,
                {
                    "role": "system",
                    "content": f"你是 AgentForge 平台助手，正在按技能「{name}」处理请求。"
                    f"技能指令如下：\n{markdown}",
                },
            )
        try:
            data = self._chat(messages)
            message = self._message(data)
            content = message.get("content")
            reasoning = (
                message.get("reasoning_content")
                or message.get("reasoning")
                or message.get("thinking")
                or ""
            )
            if reasoning:
                import logging
                logging.getLogger(__name__).info("model reasoning length=%d", len(reasoning))
            return {"content": self._safe_natural_reply(prompt, content), "reasoning": reasoning}
        except Exception as exc:
            raise _model_call_error("生成回复失败", exc) from exc

    def plan_tool(self, prompt: str, tools: list[dict[str, Any]]) -> dict[str, Any] | None:
        """用真实 LLM 做工具路由，并兼容标准/非标准 tool_calls 返回格式。"""
        if not tools:
            return None
        tool_desc = "\n".join(
            f"- {t.get('code')}: {t.get('description', '') or t.get('name', '')}" for t in tools
        )
        system = (
            "你是工具路由。根据用户输入从下列工具中选择一个调用，"
            "严格只输出 JSON：{\"tool_code\":\"<编码或null>\",\"arguments\":{...}}。"
            "不要输出任何解释文字。若无需调用工具，tool_code 为 null。"
        )
        messages = [
            {"role": "system", "content": system + "\n可用工具:\n" + tool_desc},
            {"role": "user", "content": prompt},
        ]
        try:
            data = self._chat(messages)
            parsed_plan = self._extract_tool_plan(data, tools)
            if parsed_plan is not None:
                return parsed_plan
        except Exception as exc:
            raise _model_call_error("工具规划失败", exc) from exc
        return None

    def build_tool_reply(self, tool_code: str, result: dict[str, Any]) -> str:
        """工具执行后，用真实 LLM 基于结果生成自然语言总结。"""
        messages = [
            {
                "role": "user",
                "content": f"工具 {tool_code} 返回结果：\n"
                f"{json.dumps(result, ensure_ascii=False)}\n\n请用简洁中文总结这个工具结果。",
            }
        ]
        try:
            data = self._chat(messages)
            content = self._message(data).get("content")
            return self._safe_natural_reply("请总结工具结果", content)
        except Exception as exc:
            raise _model_call_error("工具结果总结失败", exc) from exc

    async def astream(self, prompt: str) -> AsyncIterator[str]:
        yield self.build_reply(prompt)
