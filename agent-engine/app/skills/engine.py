import logging
import re
import time
import uuid

from app.skills.registry import SkillRegistryClient
from app.skills.schemas import SkillDescriptor, SkillExecutionResult, SkillStep
from app.tools.gateway import ToolGateway
from app.tools.schemas import ToolCallRequest, ToolDescriptor

logger = logging.getLogger(__name__)

_EXPR_SPLIT_PATTERN = re.compile(r"[，,;；、\n]|和|以及")
_EXPR_LEADING = re.compile(r"^(请|帮我|麻烦|计算|分步计算|多步计算|逐步计算|一下|分别|：|:|\s)*")
_EXPR_TRAILING = re.compile(r"[\s。.！!？?]*$")
_EXPR_SCAN = re.compile(
    r"\d+(?:\.\d+)?(?:\s*[+\-*/%^()]\s*(?:\d+(?:\.\d+)?|\([^)]*\)))*"
)


class SkillEngine:
    """技能执行引擎：触发匹配由 matcher 负责，这里负责执行。

    双通道执行：
    - 通道 A（steps 编排，DB content_json 兼容）：逐个 step 调工具、模板渲染；
    - 通道 B（SKILL.md 提示词注入，主通道）：全文注入 system 域 + allowed_tools
      收窄工具白名单（只收窄不放大），由模型在受限工具集上决策。

    每个工具调用都是标准 ToolGateway 调用：内部产生 tool_call_* 事件（tool_events），
    由上层转发给 Java 做工具级审计（message_tool_call）；技能整体产生 skill_call_*
    （skill 级审计）。
    """

    def __init__(self, settings, model=None) -> None:
        self.registry = SkillRegistryClient(settings)
        self.model = model
        if self.model is None:
            from app.model.factory import create_model
            self.model = create_model(settings).model

    async def list_skills(self, tenant_id: int = 1) -> list[SkillDescriptor]:
        return await self.registry.list_meta(tenant_id)

    async def run(
        self,
        prompt: str,
        descriptor: SkillDescriptor,
        tool_descriptors: list[dict],
        gateway: ToolGateway,
        conversation_id: str,
        trace_id: str,
        tenant_id: int,
    ) -> SkillExecutionResult:
        started = time.perf_counter()
        detail = await self.registry.get_detail(descriptor.code, tenant_id)
        if detail is None or detail.content is None:
            return SkillExecutionResult(
                status="error",
                error_code="SKILL_CONTENT_MISSING",
                error_message=f"技能缺少执行定义: {descriptor.code}",
                duration_ms=self._elapsed(started),
            )

        # 通道 A：steps 编排（DB content_json 兼容通道）
        if detail.content.steps:
            return await self._run_step_skill(
                prompt, detail, tool_descriptors, gateway,
                conversation_id, trace_id, started,
            )

        # 通道 B：SKILL.md 提示词注入（主通道）
        return await self._run_prompt_skill(
            prompt, detail, tool_descriptors, gateway,
            conversation_id, trace_id, started,
        )

    async def _run_step_skill(
        self,
        prompt: str,
        detail: SkillDescriptor,
        tool_descriptors: list[dict],
        gateway: ToolGateway,
        conversation_id: str,
        trace_id: str,
        started: float,
    ) -> SkillExecutionResult:
        tools = [ToolDescriptor.model_validate(item) for item in tool_descriptors]
        args_map = self._extract_skill_args(prompt, detail.code)
        step_outputs: list[dict] = []
        tool_events: list[dict] = []

        for step in detail.content.steps:
            if step.repeat == "list":
                items = args_map.get("expressions") or []
                if not items:
                    return SkillExecutionResult(
                        status="error",
                        error_code="SKILL_ARGUMENT_MISSING",
                        error_message="缺少可重复执行的表达式，请用逗号或「和」分隔多个算式",
                        duration_ms=self._elapsed(started),
                    )
                for index, item in enumerate(items):
                    call_args = self._render_args(step.args, {"expression": item})
                    outcome = await self._call_tool(
                        step, call_args, tools, gateway, conversation_id, trace_id, tool_events
                    )
                    outcome["index"] = index
                    outcome["expression"] = item
                    step_outputs.append(outcome)
                    if outcome["status"] != "success":
                        return self._failed_result(outcome, step_outputs, tool_events, started)
            else:
                call_args = self._render_args(step.args, args_map)
                outcome = await self._call_tool(
                    step, call_args, tools, gateway, conversation_id, trace_id, tool_events
                )
                step_outputs.append(outcome)
                if outcome["status"] != "success":
                    return self._failed_result(outcome, step_outputs, tool_events, started)

        reply = self._render_prompt(detail.content.prompt, step_outputs, detail.code)
        return SkillExecutionResult(
            status="success",
            result=reply,
            duration_ms=self._elapsed(started),
            step_outputs=step_outputs,
            tool_events=tool_events,
        )

    async def _run_prompt_skill(
        self,
        prompt: str,
        detail: SkillDescriptor,
        tool_descriptors: list[dict],
        gateway: ToolGateway,
        conversation_id: str,
        trace_id: str,
        started: float,
    ) -> SkillExecutionResult:
        """SKILL.md 提示词技能：注入 system 域 + 工具白名单收窄（只收窄不放大）。"""
        content = detail.content
        allowed = set(content.allowed_tools)
        tools = [item for item in tool_descriptors if item.get("code") in allowed]
        tool_events: list[dict] = []

        plan = self.model.plan_tool(prompt, tools) if self.model is not None else None
        if plan is not None:
            tool_code = str(plan.get("tool_code", ""))
            call_args = dict(plan.get("arguments", {}))
            step = SkillStep(name=tool_code, tool=tool_code, args=call_args)
            outcome = await self._call_tool(
                step, call_args,
                [ToolDescriptor.model_validate(item) for item in tools],
                gateway, conversation_id, trace_id, tool_events,
            )
            if outcome["status"] != "success":
                return self._failed_result(outcome, [], tool_events, started)
            reply = self.model.build_tool_reply(
                tool_code, {"status": "success", "result": outcome["result"]},
            )
        else:
            skill_ctx = {
                "code": detail.code,
                "name": detail.name,
                "markdown": content.markdown,
            }
            reply = self.model.build_reply(prompt, skill=skill_ctx) if self.model is not None else content.markdown

        return SkillExecutionResult(
            status="success",
            result=reply,
            duration_ms=self._elapsed(started),
            step_outputs=[],
            tool_events=tool_events,
        )

    async def _call_tool(
        self,
        step: SkillStep,
        call_args: dict,
        tools: list[ToolDescriptor],
        gateway: ToolGateway,
        conversation_id: str,
        trace_id: str,
        tool_events: list[dict],
    ) -> dict:
        tool = next((t for t in tools if t.code == step.tool), None)
        call_id = uuid.uuid4().hex
        if tool is None:
            tool_events.append(self._tool_event(
                "tool_call_error", call_id, None, step.tool, step.tool,
                call_args, "error", "TOOL_NOT_FOUND", f"技能依赖的工具不存在: {step.tool}", 0))
            return {"status": "error", "error_code": "TOOL_NOT_FOUND",
                    "error_message": f"技能依赖的工具不存在: {step.tool}", "duration_ms": 0}

        tool_events.append(self._tool_event(
            "tool_call_start", call_id, tool.id, tool.code, tool.name, call_args, "running"))
        request = ToolCallRequest(
            call_id=call_id,
            tool_code=tool.code,
            arguments=call_args,
            conversation_id=conversation_id,
            trace_id=trace_id,
        )
        result = await gateway.execute(request, tool)
        payload = result.model_dump()
        event_type = "tool_call_result" if result.status == "success" else "tool_call_error"
        tool_events.append(self._tool_event(
            event_type, call_id, tool.id, tool.code, tool.name, call_args,
            payload.get("status"), payload.get("error_code"),
            payload.get("error_message"), payload.get("duration_ms", 0),
            result=payload.get("result")))
        return {
            "status": result.status,
            "result": payload.get("result"),
            "error_code": payload.get("error_code"),
            "error_message": payload.get("error_message"),
            "duration_ms": payload.get("duration_ms", 0),
        }

    def _tool_event(self, event_type: str, call_id: str, tool_id: int | None,
                    tool_code: str, tool_name: str,
                    arguments: dict, status: str, error_code: str | None = None,
                    error_message: str | None = None, duration_ms: int = 0,
                    result=None) -> dict:
        return {
            "type": event_type,
            "data": {
                "call_id": call_id,
                "tool_id": tool_id,
                "tool_code": tool_code,
                "tool_name": tool_name,
                "arguments": arguments,
                "result": result,
                "status": status,
                "error_code": error_code,
                "error_message": error_message,
                "duration_ms": duration_ms,
            },
        }

    def _failed_result(self, outcome: dict, step_outputs: list[dict],
                       tool_events: list[dict], started: float) -> SkillExecutionResult:
        return SkillExecutionResult(
            status="error" if outcome["status"] == "error" else "timeout",
            error_code=outcome.get("error_code"),
            error_message=outcome.get("error_message"),
            duration_ms=self._elapsed(started),
            step_outputs=step_outputs,
            tool_events=tool_events,
        )

    def _render_args(self, template_args: dict, args_map: dict) -> dict:
        rendered: dict = {}
        for key, value in template_args.items():
            if isinstance(value, str) and "{" in value:
                rendered[key] = value.format(**args_map)
            else:
                rendered[key] = value
        return rendered

    def _extract_skill_args(self, prompt: str, skill_code: str) -> dict:
        if skill_code == "multi_step_calc":
            return {"expressions": self._extract_expressions(prompt)}
        return {}

    def _extract_expressions(self, prompt: str) -> list[str]:
        expressions: list[str] = []
        for segment in _EXPR_SPLIT_PATTERN.split(prompt):
            cleaned = _EXPR_LEADING.sub("", segment.strip())
            cleaned = _EXPR_TRAILING.sub("", cleaned)
            if cleaned and re.search(r"\d", cleaned) and re.search(r"[+\-*/%^]", cleaned):
                expressions.append(cleaned)
        if expressions:
            return expressions
        return [
            match.group(0).strip()
            for match in _EXPR_SCAN.finditer(prompt)
            if re.search(r"[+\-*/%^]", match.group(0))
        ]

    def _render_prompt(self, template: str, step_outputs: list[dict], skill_code: str) -> str:
        if not template:
            return self._default_summary(step_outputs)
        try:
            if skill_code == "business_hours":
                result = (step_outputs[0].get("result") or {}) if step_outputs else {}
                display = result.get("display", "")
                is_open = self._judge_open(result)
                return template.format(display=display, is_open=is_open)
            if skill_code == "multi_step_calc":
                lines = [
                    f"{i}) {out.get('expression', '')} = {(out.get('result') or {}).get('value', '')}"
                    for i, out in enumerate(step_outputs, 1)
                ]
                return template.format(steps_summary="\n".join(lines))
        except KeyError:
            logger.warning("skill prompt placeholder mismatch skill=%s", skill_code)
        return self._default_summary(step_outputs)

    def _judge_open(self, result: dict) -> str:
        iso = str(result.get("iso", ""))
        hour_min = iso[11:16] if len(iso) >= 16 else ""
        if not hour_min:
            return "营业状态未知"
        return "正在营业中" if "09:00" <= hour_min < "22:00" else "已打烊"

    def _default_summary(self, step_outputs: list[dict]) -> str:
        lines = []
        for index, out in enumerate(step_outputs, 1):
            result = out.get("result")
            lines.append(f"{index}) {out.get('expression', out.get('step', '步骤'))}: {result}")
        return "\n".join(lines) or "技能执行完成"

    def _elapsed(self, started: float) -> int:
        return max(0, int((time.perf_counter() - started) * 1000))
