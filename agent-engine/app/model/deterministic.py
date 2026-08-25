from collections.abc import AsyncIterator
import re
from typing import Any


class DeterministicModel:
    """无外部 API Key 时的可运行模型，用于验证真实 LangGraph 管道。"""

    def __init__(self, model_name: str, chunk_size: int = 18) -> None:
        self.model_name = model_name
        self.chunk_size = chunk_size

    def build_reply(self, prompt: str, skill: dict[str, Any] | None = None) -> str:
        raw_prompt = str(prompt or "").strip()
        user_prompt = self._latest_user_prompt(raw_prompt)
        compact = " ".join(user_prompt.split())
        normalized = compact.lower().strip()
        system_hint = ""
        if "系统指令：" in raw_prompt:
            system_hint = raw_prompt.split("系统指令：", 1)[1].split("用户请求：", 1)[0].strip()
        if skill:
            name = str(skill.get("name", ""))
            markdown = str(skill.get("markdown", ""))
            hint = " ".join(markdown.strip().split())[:120]
            return (
                f"已按技能「{name}」处理你的请求。\n\n"
                f"技能指令摘要：{hint}\n\n"
                f"当前请求：{user_prompt or '（空输入）'}"
            )
        if not user_prompt:
            return "你好！我是 AgentForge 智能助手，请告诉我你想了解或处理什么问题。"
        if "只回复" in user_prompt or "请直接回答" in user_prompt:
            direct = re.split(r"(?:只回复|直接回答)\s*[:：]?", user_prompt, maxsplit=1)[-1].strip(" \t:：")
            if re.search(r"1\s*[+＋]\s*1", direct):
                return "1+1 等于 2。"
            if direct:
                return direct
        if normalized in {"你好", "您好", "嗨", "hello", "hi"} or normalized.startswith(("你好！", "你好,")):
            return "你好！我是 AgentForge 智能助手，可以回答问题、调用工具、检索知识库并协助执行已绑定的技能。"
        if "介绍" in user_prompt and "agentforge" in normalized:
            return (
                "AgentForge 是一个企业级 AI Agent 平台，提供模型接入、Agent 创建与发布、"
                "工具和技能编排、知识库检索，以及基于 SSE 的流式对话能力。"
            )
        if "你能回答什么" in user_prompt or "能做什么" in user_prompt:
            return "我可以进行自然语言问答，执行计算和单位换算，查询当前时间，检索已授权知识库，并按 Agent 配置调用工具或技能。"
        if "只回复" in user_prompt or "请直接回答" in user_prompt:
            direct = re.split(r"(?:只回复|直接回答)\s*[:：]?", user_prompt, maxsplit=1)[-1].strip(" \t:：")
            if re.search(r"1\s*[+＋]\s*1", direct):
                return "1+1 等于 2。"
            if direct:
                return direct
        if re.search(r"1\s*[+＋]\s*1", user_prompt):
            return "1+1 等于 2。"
        return (
            f"我已收到你的问题：{user_prompt}\n\n"
            "当前使用的是确定性模型，因此我不能像真实大模型一样生成开放域答案；"
            "但聊天链路已正常工作。若要获得更完整的回答，请选择已配置的真实模型。"
            + (f"\n\n已应用 Agent 系统指令：{system_hint}" if system_hint else "")
        )

    @staticmethod
    def _latest_user_prompt(content: str) -> str:
        value = str(content or "").strip()
        if not value:
            return ""
        matches = list(re.finditer(r"(?:^|\n)user\s*:\s*(.*?)(?=\n(?:user|assistant)\s*:|$)", value, re.S | re.I))
        if matches and matches[-1].group(1).strip():
            return matches[-1].group(1).strip()
        return value

    def plan_tool(self, prompt: str, tools: list[dict[str, Any]]) -> dict[str, Any] | None:
        available = {str(tool["code"]): tool for tool in tools if tool.get("enabled", True)}
        code = self._extract_code(prompt)
        if code and "code_exec" in available:
            return {"tool_code": "code_exec", "arguments": {"code": code}}
        expression = self._extract_expression(prompt)
        if expression and "calculator" in available:
            return {"tool_code": "calculator", "arguments": {"expression": expression}}
        if self._asks_current_time(prompt) and "get_current_time" in available:
            timezone = "Asia/Shanghai"
            return {"tool_code": "get_current_time", "arguments": {"timezone": timezone}}
        conversion = self._extract_conversion(prompt)
        if conversion and "unit_converter" in available:
            return {"tool_code": "unit_converter", "arguments": conversion}
        kb_query = self._extract_knowledge_query(prompt)
        if kb_query and "knowledge_search" in available:
            return {"tool_code": "knowledge_search", "arguments": {"query": kb_query}}
        return None

    def build_tool_reply(self, tool_code: str, result: dict[str, Any]) -> str:
        if result.get("status") != "success":
            return f"工具调用失败：{result.get('error_message', '未知错误')}"
        data = result.get("result") or {}
        if tool_code == "calculator":
            return f"计算结果：{data.get('expression')} = {data.get('value')}"
        if tool_code == "get_current_time":
            return f"当前时间（{data.get('timezone')}）：{data.get('display')}"
        if tool_code == "unit_converter":
            return f"换算结果：{data.get('display')}"
        if tool_code == "code_exec":
            return self._build_sandbox_reply(data)
        if tool_code == "knowledge_search":
            return self._build_knowledge_reply(data)
        return f"工具 {tool_code} 已执行完成，结果：{data}"

    # ------------------------------------------------------------------
    # knowledge_search 意图：知识库 / 资料 / 文档 检索
    # ------------------------------------------------------------------
    _KB_HINTS = ("知识库", "搜索文档", "查找资料", "查资料", "资料库", "检索一下", "搜索一下")

    def _extract_knowledge_query(self, prompt: str) -> str | None:
        compact = " ".join(prompt.strip().split())
        if not any(hint in compact for hint in self._KB_HINTS):
            return None
        # 取"知识库/资料"之后的内容作为 query，缺省用整句
        for marker in ("知识库", "资料库", "查资料", "查找资料", "搜索文档"):
            idx = compact.find(marker)
            if idx >= 0:
                tail = compact[idx + len(marker):].lstrip("里中的，,、:：关于的")
                if tail:
                    return tail
        return compact

    def _build_knowledge_reply(self, data: dict[str, Any]) -> str:
        results = data.get("results") or []
        if not results:
            return f"知识库中没有检索到与「{data.get('query', '')}」相关的内容。"
        lines = [f"在知识库中找到 {len(results)} 条相关片段（query: {data.get('query')}）："]
        for idx, item in enumerate(results, start=1):
            lines.append(
                f"{idx}. 来自《{item['title']}》\n"
                f"   > {item['text'][:120]}\n"
                f"   （相似度 {item['score']}，溯源 {item['doc_id']}）"
            )
        return "\n".join(lines)

    # ------------------------------------------------------------------
    # code_exec 意图：fenced 代码块优先，其次"执行/运行代码"后的文本
    # ------------------------------------------------------------------
    _CODE_FENCE = re.compile(r"```(?:python|py)?\s*\n(.*?)```", re.S)
    _CODE_INTENT = re.compile(
        r"(?:执行|运行|跑一下|帮我算一下这段)?(?:这段)?(?:python|py)?\s*代码[：:，,。\s]*(.*)", re.S
    )

    def _extract_code(self, prompt: str) -> str | None:
        fenced = self._CODE_FENCE.search(prompt)
        if fenced:
            code = fenced.group(1).strip()
            return code if code else None
        if re.search(r"执行.*代码|运行.*代码|跑.*代码|code_exec|沙箱", prompt):
            match = self._CODE_INTENT.search(prompt)
            if match and match.group(1).strip():
                return match.group(1).strip()
        return None

    def _build_sandbox_reply(self, data: dict[str, Any]) -> str:
        status = data.get("status")
        duration = data.get("duration_ms", 0)
        stdout = str(data.get("stdout") or "").strip()
        stderr = str(data.get("stderr") or "").strip()
        if status == "ok":
            body = f"```\n{stdout}\n```" if stdout else "（无输出）"
            return f"沙箱执行成功（{duration}ms）：\n{body}"
        if status == "timeout":
            return f"沙箱执行超时（{duration}ms），进程组已终止。{('输出: ' + stdout[:200]) if stdout else ''}"
        if status == "rejected":
            return f"代码未通过安全预检：{stderr}"
        return f"沙箱执行失败（退出码 {data.get('exit_code')}）：{stderr or '未知错误'}"

    def _extract_expression(self, prompt: str) -> str | None:
        compact = " ".join(prompt.strip().split())
        candidates = re.findall(r"(?<![A-Za-z])[-+*/%().\d\s]{3,}", compact)
        for candidate in reversed(candidates):
            expression = candidate.strip().rstrip("。？?！!")
            if expression and (
                re.search(r"[+*/%]", expression)
                or "-" in expression[1:]
            ):
                return expression
        return None

    def _asks_current_time(self, prompt: str) -> bool:
        compact = prompt.lower()
        return any(word in compact for word in ("几点", "当前时间", "现在时间", "现在几号", "今天日期"))

    # 中文单位词 → 工具标准名（与 unit_converter 工具 _UNIT_CATEGORY 对齐）
    _CN_UNITS: dict[str, str] = {
        "毫米": "mm", "厘米": "cm", "分米": "dm", "米": "m", "千米": "km", "公里": "km",
        "英寸": "inch", "英尺": "ft", "英里": "mile",
        "毫克": "mg", "克": "g", "千克": "kg", "公斤": "kg", "吨": "t",
        "斤": "jin", "磅": "lb", "盎司": "oz",
        "平方米": "m2", "平方千米": "km2", "平方公里": "km2", "公顷": "ha", "亩": "mu",
        "平方英尺": "sqft", "摄氏度": "celsius", "华氏度": "fahrenheit",
    }

    _CONVERSION_PATTERN = re.compile(
        r"(?P<value>\d+(?:\.\d+)?)\s*"
        r"(?P<from_unit>毫米|厘米|分米|米|千米|公里|英寸|英尺|英里|"
        r"毫克|克|千克|公斤|吨|斤|磅|盎司|"
        r"平方米|平方千米|平方公里|公顷|亩|平方英尺|摄氏度|华氏度)"
        r"\s*(?:等于多少|等于|是多少|换成|换算成|换算为|转换为|转成|转|→|->|到)?\s*"
        r"(?P<to_unit>毫米|厘米|分米|米|千米|公里|英寸|英尺|英里|"
        r"毫克|克|千克|公斤|吨|斤|磅|盎司|"
        r"平方米|平方千米|平方公里|公顷|亩|平方英尺|摄氏度|华氏度)"
    )

    def _extract_conversion(self, prompt: str) -> dict[str, Any] | None:
        compact = " ".join(prompt.strip().split())
        match = self._CONVERSION_PATTERN.search(compact)
        if not match:
            return None
        from_unit = self._CN_UNITS.get(match.group("from_unit"))
        to_unit = self._CN_UNITS.get(match.group("to_unit"))
        if not from_unit or not to_unit:
            return None
        return {
            "value": float(match.group("value")),
            "from_unit": from_unit,
            "to_unit": to_unit,
        }

    async def astream(self, prompt: str) -> AsyncIterator[str]:
        reply = self.build_reply(prompt)
        for start in range(0, len(reply), self.chunk_size):
            yield reply[start:start + self.chunk_size]
