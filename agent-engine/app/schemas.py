import json
from typing import Any

from pydantic import BaseModel, Field


class AgentRuntimeConfig(BaseModel):
    """已发布 Agent 的运行时配置，资源列表为空表示不额外限制。"""

    agent_id: int | None = Field(default=None, ge=1)
    agent_code: str | None = Field(default=None, max_length=128)
    system_prompt: str = Field(default="", max_length=50_000)
    tool_ids: list[int] = Field(default_factory=list)
    skill_ids: list[int] = Field(default_factory=list)
    knowledge_doc_ids: list[str] = Field(default_factory=list, max_length=100)


class ChatStreamRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=100_000)
    conversation_id: str = Field(min_length=1, max_length=64)
    trace_id: str | None = Field(default=None, max_length=64)
    tenant_id: int = Field(default=1, ge=1)
    # 请求级模型配置：None=引擎默认(deterministic)；传 dict 则切换为真实 LLM
    llm_config: dict | None = Field(default=None)
    # 已发布 Agent 运行时配置：系统提示词 + 资源绑定
    agent_config: AgentRuntimeConfig | None = Field(default=None)


class EngineEvent(BaseModel):
    type: str
    data: dict[str, Any]
    trace_id: str

    def to_ndjson(self) -> str:
        return json.dumps(self.model_dump(), ensure_ascii=False, separators=(",", ":")) + "\n"
