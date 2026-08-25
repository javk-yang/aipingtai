from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ToolDescriptor(BaseModel):
    """Java 注册中心与 Python Tool Gateway 共享的工具描述。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    id: int | None = None
    code: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=128)
    description: str = Field(default="", max_length=512)
    input_schema: dict[str, Any]
    output_schema: dict[str, Any] | None = None
    executor_type: Literal["builtin", "mcp"] = "builtin"
    transport: Literal["stdio", "sse", "http"] | None = None
    executor_config: dict[str, Any] = Field(default_factory=dict)
    timeout_ms: int = Field(default=30_000, ge=100, le=600_000)
    enabled: bool = True


class ToolCallRequest(BaseModel):
    """工具调用标准请求。"""

    call_id: str = Field(min_length=1, max_length=64)
    tool_code: str = Field(min_length=1, max_length=128)
    arguments: dict[str, Any] = Field(default_factory=dict)
    conversation_id: str = Field(min_length=1, max_length=64)
    trace_id: str = Field(min_length=1, max_length=64)


class ToolCallResult(BaseModel):
    """工具调用标准结果，不把实现异常直接抛给图节点。"""

    call_id: str
    tool_code: str
    status: Literal["success", "error", "timeout"]
    result: Any | None = None
    error_code: str | None = None
    error_message: str | None = None
    duration_ms: int = Field(default=0, ge=0)


class ToolStreamEvent(BaseModel):
    """图执行期间输出给 Java 的工具生命周期事件。"""

    type: Literal["tool_call_start", "tool_call_result", "tool_call_error"]
    call_id: str
    tool_id: int | None = None
    tool_code: str
    tool_name: str
    arguments: dict[str, Any] | None = None
    result: Any | None = None
    status: Literal["running", "success", "error", "timeout"]
    error_code: str | None = None
    error_message: str | None = None
    duration_ms: int = Field(default=0, ge=0)
