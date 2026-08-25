from typing import Annotated, TypedDict

from langgraph.graph.message import add_messages


class AgentState(TypedDict):
    """LangGraph 状态：消息列表由 add_messages reducer 负责追加合并。"""

    messages: Annotated[list, add_messages]
    conversation_id: str
    trace_id: str
    tenant_id: int
    model_name: str
    system_prompt: str
    knowledge_doc_ids: list[str]
    tool_descriptors: list[dict]
    pending_tool_call: dict | None
    tool_result: dict | None
    tool_rounds: int
    skill_descriptors: list[dict]
    pending_skill: dict | None
    skill_result: dict | None
    skill_rounds: int
