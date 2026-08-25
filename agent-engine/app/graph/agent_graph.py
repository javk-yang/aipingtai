from collections.abc import AsyncIterator
from typing import Any
import asyncio
import re
import uuid

from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph

from app.config import Settings
from app.graph.state import AgentState
from app.model.factory import create_model
from app.skills.engine import SkillEngine
from app.skills.matcher import match_skill
from app.skills.schemas import SkillDescriptor
from app.tools.gateway import ToolGateway
from app.tools.schemas import ToolCallRequest, ToolDescriptor


class AgentGraph:
    """LangGraph ReAct 回路：技能路由优先，其次工具，兜底普通回复。

    START → agent → (命中技能) skill_start → skill_execute → agent
                   → (命中工具) tool_start → tools → agent
                   → (无) END
    """

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        bundle = create_model(settings)
        self.model = bundle.model
        self.provider = bundle.provider
        self._default_model = bundle.model
        self._default_provider = bundle.provider
        self.max_tool_rounds = settings.tool_max_rounds
        self.tool_gateway = ToolGateway(settings)
        self.skill_engine = SkillEngine(settings, model=self.model)
        self.checkpointer = MemorySaver()
        self._lock = asyncio.Lock()
        self.graph = self._build_graph()

    def _build_graph(self):
        builder = StateGraph(AgentState)
        builder.add_node("agent", self._agent_node)
        builder.add_node("skill_start", self._skill_start_node)
        builder.add_node("skill_execute", self._skill_execute_node)
        builder.add_node("tool_start", self._tool_start_node)
        builder.add_node("tools", self._tools_node)
        builder.add_edge(START, "agent")
        builder.add_conditional_edges(
            "agent",
            self._route_after_agent,
            {
                "skill": "skill_start",
                "tools": "tool_start",
                "end": END,
            },
        )
        builder.add_edge("skill_start", "skill_execute")
        builder.add_edge("skill_execute", "agent")
        builder.add_edge("tool_start", "tools")
        builder.add_edge("tools", "agent")
        return builder.compile(checkpointer=self.checkpointer)

    async def _agent_node(self, state: AgentState) -> dict[str, Any]:
        # 技能结果回填：技能已产出最终回答，直接返回
        if state.get("skill_result") is not None:
            reply = str((state["skill_result"] or {}).get("result", ""))
            return {
                "messages": [AIMessage(content=reply)],
                "skill_result": None,
                "pending_skill": None,
            }

        # 工具结果回填：工具执行后由模型生成自然语言回复
        if state.get("tool_result") is not None:
            pending = state.get("pending_tool_call") or {}
            reply = self.model.build_tool_reply(
                str(pending.get("tool_code", "unknown")),
                state["tool_result"],
            )
            return {
                "messages": [AIMessage(content=reply)],
                "pending_tool_call": None,
                "tool_result": None,
            }

        last_human = next(
            (message for message in reversed(state["messages"]) if isinstance(message, HumanMessage)),
            HumanMessage(content=""),
        )
        prompt = self._latest_user_prompt(str(last_human.content))
        system_prompt = str(state.get("system_prompt", "")).strip()
        if system_prompt:
            prompt = f"系统指令：{system_prompt}\n\n用户请求：{prompt}"

        # 1) 技能路由（优先级最高）：只依赖元数据层，命中才进 skill_execute 拉全文
        skills = [SkillDescriptor.model_validate(item) for item in state.get("skill_descriptors", [])]
        matched = match_skill(prompt, skills)
        if matched is not None:
            return {
                "pending_skill": {
                    "call_id": uuid.uuid4().hex,
                    "descriptor": matched.model_dump(),
                },
                "skill_rounds": state.get("skill_rounds", 0) + 1,
            }

        # 2) 工具路由
        if state.get("tool_rounds", 0) < self.max_tool_rounds:
            plan = self.model.plan_tool(prompt, state.get("tool_descriptors", []))
            if plan is not None:
                arguments = dict(plan.get("arguments", {}))
                if plan.get("tool_code") == "knowledge_search":
                    allowed_docs = state.get("knowledge_doc_ids", [])
                    arguments["doc_ids"] = list(allowed_docs)
                return {
                    "pending_tool_call": {
                        "call_id": uuid.uuid4().hex,
                        **plan,
                        "arguments": arguments,
                    },
                    "tool_rounds": state.get("tool_rounds", 0) + 1,
                }

        # 3) 普通回复
        return {"messages": [AIMessage(content=self.model.build_reply(prompt))]}

    def _skill_start_node(self, state: AgentState) -> dict[str, Any]:
        # 单独节点：确保 skill_call_start 事件在真正执行前发出
        return {"pending_skill": state["pending_skill"]}

    async def _skill_execute_node(self, state: AgentState) -> dict[str, Any]:
        pending = state.get("pending_skill") or {}
        descriptor = SkillDescriptor.model_validate(pending["descriptor"])
        result = await self.skill_engine.run(
            prompt=self._last_prompt(state),
            descriptor=descriptor,
            tool_descriptors=state.get("tool_descriptors", []),
            gateway=self.tool_gateway,
            conversation_id=state["conversation_id"],
            trace_id=state["trace_id"],
            tenant_id=state["tenant_id"],
        )
        return {
            "skill_result": {
                **result.model_dump(),
                "call_id": pending.get("call_id"),
                "skill_code": descriptor.code,
                "skill_name": descriptor.name,
                "skill_id": descriptor.id,
                "skill_version": descriptor.version,
            },
            "pending_skill": None,
        }

    async def _tool_start_node(self, state: AgentState) -> dict[str, Any]:
        return {"pending_tool_call": state["pending_tool_call"]}

    async def _tools_node(self, state: AgentState) -> dict[str, Any]:
        pending = state.get("pending_tool_call") or {}
        tool_code = str(pending.get("tool_code", ""))
        descriptor = next(
            (
                ToolDescriptor.model_validate(item)
                for item in state.get("tool_descriptors", [])
                if item.get("code") == tool_code
            ),
            None,
        )
        if descriptor is None:
            result = {
                "call_id": str(pending.get("call_id", "")),
                "tool_code": tool_code,
                "status": "error",
                "result": None,
                "error_code": "TOOL_NOT_FOUND",
                "error_message": f"工具不存在或已禁用: {tool_code}",
                "duration_ms": 0,
            }
        else:
            request = ToolCallRequest(
                call_id=str(pending["call_id"]),
                tool_code=tool_code,
                arguments=dict(pending.get("arguments", {})),
                conversation_id=state["conversation_id"],
                trace_id=state["trace_id"],
            )
            result = (await self.tool_gateway.execute(request, descriptor)).model_dump()
        return {"tool_result": result}

    def _route_after_agent(self, state: AgentState) -> str:
        if state.get("pending_skill") is not None:
            return "skill"
        if state.get("pending_tool_call") is not None:
            return "tools"
        return "end"

    def _latest_user_prompt(self, content: str) -> str:
        """从 Java 拼接的历史上下文中提取最后一条 user 内容。

        新请求通常形如 ``user: 旧问题\nassistant: 旧回答\nuser: 当前问题``。
        只把当前问题交给路由和模型，避免历史中的演示文案或旧协议污染本轮回答。
        """
        value = str(content or "").strip()
        if not value:
            return ""
        matches = list(re.finditer(r"(?:^|\n)user\s*:\s*(.*?)(?=\n(?:user|assistant)\s*:|$)", value, re.S | re.I))
        if matches:
            latest = matches[-1].group(1).strip()
            if latest:
                return latest
        return value

    def _last_prompt(self, state: AgentState) -> str:
        last_human = next(
            (message for message in reversed(state["messages"]) if isinstance(message, HumanMessage)),
            HumanMessage(content=""),
        )
        return self._latest_user_prompt(str(last_human.content))

    async def stream(
        self,
        prompt: str,
        conversation_id: str,
        trace_id: str,
        tenant_id: int = 1,
        model_config: dict | None = None,
        agent_config: dict | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        # 请求级模型注入：按 model_config 创建真实/确定性模型，结束后恢复默认。
        # 加锁是为了避免并发请求修改单例上的 self.model / self.skill_engine.model
        # 导致模型实例被错用（如真实模型请求未结束时，确定性请求误走真实模型）。
        async with self._lock:
            bundle = create_model(self.settings, model_config)
            prev_model, prev_provider, prev_skill_model = (
                self.model,
                self.provider,
                self.skill_engine.model,
            )
            self.model = bundle.model
            self.provider = bundle.provider
            self.skill_engine.model = bundle.model
            try:
                descriptors = await self.tool_gateway.list_tools(tenant_id)
                allowed_tool_ids = set((agent_config or {}).get("tool_ids") or [])
                if agent_config is not None:
                    descriptors = [tool for tool in descriptors if tool.id in allowed_tool_ids]
                descriptor_dicts = [tool.model_dump() for tool in descriptors]
                descriptor_names = {tool.code: tool.name for tool in descriptors}
                skills = await self.skill_engine.list_skills(tenant_id)
                allowed_skill_ids = set((agent_config or {}).get("skill_ids") or [])
                if agent_config is not None:
                    skills = [skill for skill in skills if skill.id in allowed_skill_ids]
                skill_dicts = [skill.model_dump() for skill in skills]
                skill_names = {skill.code: skill.name for skill in skills}
                input_state: AgentState = {
                    "messages": [HumanMessage(content=prompt)],
                    "conversation_id": conversation_id,
                    "trace_id": trace_id,
                    "tenant_id": tenant_id,
                    "model_name": self.model.model_name,
                    "system_prompt": str((agent_config or {}).get("system_prompt") or ""),
                    "knowledge_doc_ids": [str(item) for item in ((agent_config or {}).get("knowledge_doc_ids") or [])],
                    "tool_descriptors": descriptor_dicts,
                    "pending_tool_call": None,
                    "tool_result": None,
                    "tool_rounds": 0,
                    "skill_descriptors": skill_dicts,
                    "pending_skill": None,
                    "skill_result": None,
                    "skill_rounds": 0,
                }
                config = {
                    "configurable": {"thread_id": conversation_id},
                    "recursion_limit": 8 * self.max_tool_rounds + 8,
                }
                async for update in self.graph.astream(input_state, config=config, stream_mode="updates"):
                    if "skill_start" in update:
                        pending = update["skill_start"].get("pending_skill") or {}
                        descriptor = pending.get("descriptor") or {}
                        skill_code = descriptor.get("code", "")
                        yield {
                            "type": "skill_call_start",
                            "data": {
                                "call_id": pending.get("call_id"),
                                "skill_id": descriptor.get("id"),
                                "skill_code": skill_code,
                                "skill_name": descriptor.get("name", skill_names.get(skill_code, skill_code)),
                                "skill_version": descriptor.get("version"),
                                "call_args": {"prompt": prompt[:512]},
                                "status": "running",
                            },
                        }
                    if "skill_execute" in update:
                        result = update["skill_execute"].get("skill_result") or {}
                        for tool_event in result.get("tool_events", []):
                            yield tool_event
                        event_type = "skill_call_result" if result.get("status") == "success" else "skill_call_error"
                        yield {
                            "type": event_type,
                            "data": {
                                "call_id": result.get("call_id"),
                                "skill_id": result.get("skill_id"),
                                "skill_code": result.get("skill_code"),
                                "skill_name": result.get("skill_name"),
                                "skill_version": result.get("skill_version"),
                                "result": result.get("result"),
                                "status": result.get("status"),
                                "error_code": result.get("error_code"),
                                "error_message": result.get("error_message"),
                                "duration_ms": result.get("duration_ms", 0),
                            },
                        }
                    if "tool_start" in update:
                        pending = update["tool_start"].get("pending_tool_call") or {}
                        tool_code = str(pending.get("tool_code", ""))
                        yield {
                            "type": "tool_call_start",
                            "data": {
                                "call_id": pending.get("call_id"),
                                "tool_id": next((tool.id for tool in descriptors if tool.code == tool_code), None),
                                "tool_code": tool_code,
                                "tool_name": descriptor_names.get(tool_code, tool_code),
                                "arguments": pending.get("arguments", {}),
                                "status": "running",
                            },
                        }
                    if "tools" in update:
                        result = update["tools"].get("tool_result") or {}
                        tool_code = str(result.get("tool_code", ""))
                        event_type = "tool_call_result" if result.get("status") == "success" else "tool_call_error"
                        yield {
                            "type": event_type,
                            "data": {
                                "call_id": result.get("call_id"),
                                "tool_id": next((tool.id for tool in descriptors if tool.code == tool_code), None),
                                "tool_code": tool_code,
                                "tool_name": descriptor_names.get(tool_code, tool_code),
                                "result": result.get("result"),
                                "status": result.get("status"),
                                "error_code": result.get("error_code"),
                                "error_message": result.get("error_message"),
                                "duration_ms": result.get("duration_ms", 0),
                            },
                        }
                    if "agent" in update:
                        messages = update["agent"].get("messages", [])
                        if messages and isinstance(messages[-1], AIMessage):
                            yield {"type": "assistant_final", "data": {"content": str(messages[-1].content)}}
            finally:
                self.model = prev_model
                self.provider = prev_provider
                self.skill_engine.model = prev_skill_model
