import pytest

from app.config import Settings
from app.skills.engine import SkillEngine
from app.skills.matcher import match_skill
from app.skills.schemas import SkillDescriptor
from app.tools.builtin import BUILTIN_DESCRIPTORS
from app.tools.gateway import ToolGateway

# ---------------------------------------------------------------------------
# SKILL.md 提示词技能（主通道：markdown + allowed_tools）
# ---------------------------------------------------------------------------

UNIT_CONVERTER = {
    "id": 3,
    "code": "unit_converter",
    "name": "单位换算",
    "description": "把自然语言中的单位换算转为精确结果",
    "triggers": [
        {"type": "keyword", "values": ["换算", "转换", "单位", "等于多少"]},
        {"type": "regex", "pattern": r"\d+\s*(米|厘米|千米|公里|公斤|千克|克|斤|磅|摄氏度|华氏度|平方米|亩|公顷)"},
    ],
    "content": {
        "markdown": "当用户请求单位换算时使用本技能。\n\n## 执行步骤\n1. 提取「数值 + 源单位 + 目标单位」三元组\n2. 调用 unit_converter 工具",
        "allowed_tools": ["unit_converter"],
    },
    "version": "1.0.0",
    "enabled": True,
    "builtin": True,
}

TEXT_POLISH = {
    "id": 4,
    "code": "text_polish",
    "name": "文案润色",
    "description": "对用户提供的文案进行润色或改写",
    "triggers": [
        {"type": "keyword", "values": ["润色", "改写", "翻译", "美化", "润一下"]}
    ],
    "content": {
        "markdown": "对用户提供的文案进行润色、改写或翻译，保持原意并提升表达质量。\n\n## 执行步骤\n1. 理解原文意图\n2. 输出润色后的文案",
        "allowed_tools": [],
    },
    "version": "1.0.0",
    "enabled": True,
    "builtin": True,
}

# ---------------------------------------------------------------------------
# 兼容通道（DB content_json：steps 步骤编排）
# ---------------------------------------------------------------------------

BUSINESS_HOURS = {
    "id": 1,
    "code": "business_hours",
    "name": "营业时间助手",
    "description": "判断当前时间是否在营业时间内",
    "triggers": [
        {"type": "keyword", "values": ["营业时间", "还在营业", "开门", "关门", "打烊"]},
        {"type": "regex", "pattern": "几点(开|关)门"},
    ],
    "content": {
        "instructions": "当用户询问营业时间时使用本技能。",
        "steps": [
            {"name": "获取当前时间", "tool": "get_current_time",
             "args": {"timezone": "Asia/Shanghai"}}
        ],
        "prompt": "当前时间是 {display}。门店营业时间为 09:00-22:00，{is_open}。",
        "logic": {"open_start": "09:00", "open_end": "22:00"},
    },
    "version": "1.0.0",
    "enabled": True,
    "builtin": True,
}

MULTI_STEP_CALC = {
    "id": 2,
    "code": "multi_step_calc",
    "name": "分步计算",
    "description": "逐个计算多个表达式并汇总",
    "triggers": [
        {"type": "keyword", "values": ["分步计算", "多步计算", "逐步计算", "一步步算"]}
    ],
    "content": {
        "instructions": "当用户要求分步计算时使用本技能。",
        "steps": [
            {"name": "计算步骤", "tool": "calculator",
             "args": {"expression": "{expression}"}, "repeat": "list"}
        ],
        "prompt": "分步计算结果如下：\n{steps_summary}",
    },
    "version": "1.0.0",
    "enabled": True,
    "builtin": True,
}


def make_descriptor(payload: dict) -> SkillDescriptor:
    return SkillDescriptor.model_validate(payload)


@pytest.fixture
def engine() -> SkillEngine:
    return SkillEngine(Settings())


@pytest.fixture
def gateway() -> ToolGateway:
    return ToolGateway(Settings())


def tool_dicts() -> list[dict]:
    return [tool.model_dump() for tool in BUILTIN_DESCRIPTORS]


def call_codes(result) -> list[str]:
    return [event["data"]["tool_code"] for event in result.tool_events if event["type"] == "tool_call_start"]


# ---------------------------------------------------------------------------
# 匹配
# ---------------------------------------------------------------------------


async def test_match_keyword():
    skill = match_skill("帮我换算 5 公斤等于多少斤", [make_descriptor(UNIT_CONVERTER)])
    assert skill is not None and skill.code == "unit_converter"


async def test_match_regex():
    skill = match_skill("5 公里是多少米", [make_descriptor(UNIT_CONVERTER)])
    assert skill is not None and skill.code == "unit_converter"


async def test_match_none():
    skill = match_skill(
        "你好，介绍一下你自己",
        [make_descriptor(UNIT_CONVERTER), make_descriptor(TEXT_POLISH)],
    )
    assert skill is None


# ---------------------------------------------------------------------------
# 通道 B：SKILL.md 提示词注入
# ---------------------------------------------------------------------------


async def test_unit_converter_skill_executes_restricted_tool(engine, gateway, monkeypatch):
    """技能命中 → 白名单工具受限调用 → 模板回复，验证「命中→注入→受限调用」闭环。"""
    async def fake_detail(code, tenant_id=1):
        return make_descriptor(UNIT_CONVERTER)

    monkeypatch.setattr(engine.registry, "get_detail", fake_detail)
    result = await engine.run(
        "5 公斤等于多少斤",
        make_descriptor(UNIT_CONVERTER),
        tool_dicts(),
        gateway,
        "conv-skill-test",
        "trace-skill-test",
        1,
    )
    assert result.status == "success"
    assert "5 kg = 10 jin" in result.result
    assert call_codes(result) == ["unit_converter"]
    assert any(event["type"] == "tool_call_result" for event in result.tool_events)


async def test_text_polish_prompt_only_skill(engine, gateway, monkeypatch):
    """纯提示词技能（allowed_tools 为空）：不调用任何工具，全文注入上下文。"""
    async def fake_detail(code, tenant_id=1):
        return make_descriptor(TEXT_POLISH)

    monkeypatch.setattr(engine.registry, "get_detail", fake_detail)
    result = await engine.run(
        "帮我润色这句话：今天天气很好",
        make_descriptor(TEXT_POLISH),
        tool_dicts(),
        gateway,
        "conv-skill-test",
        "trace-skill-test",
        1,
    )
    assert result.status == "success"
    assert "文案润色" in result.result
    assert result.tool_events == []


async def test_allowed_tools_only_narrows_never_widens(engine, gateway, monkeypatch):
    """只收窄不放大：unit_converter 技能白名单不含 calculator，即使输入含算式也不得调用。"""
    async def fake_detail(code, tenant_id=1):
        return make_descriptor(UNIT_CONVERTER)

    monkeypatch.setattr(engine.registry, "get_detail", fake_detail)
    result = await engine.run(
        "帮我换算一下，顺便计算 1+1",
        make_descriptor(UNIT_CONVERTER),
        tool_dicts(),
        gateway,
        "conv-skill-test",
        "trace-skill-test",
        1,
    )
    assert result.status == "success"
    assert "calculator" not in call_codes(result)
    assert all(code == "unit_converter" for code in call_codes(result))


# ---------------------------------------------------------------------------
# 通道 A：steps 编排（DB content_json 兼容）
# ---------------------------------------------------------------------------


async def test_business_hours_execute(engine, gateway, monkeypatch):
    async def fake_detail(code, tenant_id=1):
        return make_descriptor(BUSINESS_HOURS)

    monkeypatch.setattr(engine.registry, "get_detail", fake_detail)
    result = await engine.run(
        "现在还在营业吗",
        make_descriptor(BUSINESS_HOURS),
        tool_dicts(),
        gateway,
        "conv-skill-test",
        "trace-skill-test",
        1,
    )
    assert result.status == "success"
    assert "正在营业中" in result.result or "已打烊" in result.result
    assert any(event["type"] == "tool_call_start" for event in result.tool_events)
    assert any(event["type"] == "tool_call_result" for event in result.tool_events)
    assert result.step_outputs[0]["status"] == "success"


async def test_multi_step_calc_execute(engine, gateway, monkeypatch):
    async def fake_detail(code, tenant_id=1):
        return make_descriptor(MULTI_STEP_CALC)

    monkeypatch.setattr(engine.registry, "get_detail", fake_detail)
    result = await engine.run(
        "分步计算 12 * 3 和 4 + 5",
        make_descriptor(MULTI_STEP_CALC),
        tool_dicts(),
        gateway,
        "conv-skill-test",
        "trace-skill-test",
        1,
    )
    assert result.status == "success"
    assert "36" in result.result
    assert "9" in result.result
    assert len(result.step_outputs) == 2
    assert all(output["status"] == "success" for output in result.step_outputs)


async def test_multi_step_missing_args(engine, gateway, monkeypatch):
    async def fake_detail(code, tenant_id=1):
        return make_descriptor(MULTI_STEP_CALC)

    monkeypatch.setattr(engine.registry, "get_detail", fake_detail)
    result = await engine.run(
        "分步计算一下",
        make_descriptor(MULTI_STEP_CALC),
        tool_dicts(),
        gateway,
        "conv-skill-test",
        "trace-skill-test",
        1,
    )
    assert result.status == "error"
    assert result.error_code == "SKILL_ARGUMENT_MISSING"


def test_extract_expressions():
    engine_obj = SkillEngine(Settings())
    assert engine_obj._extract_expressions("分步计算 12*3 和 4+5") == ["12*3", "4+5"]
    assert engine_obj._extract_expressions("多步计算 10-2，8/4") == ["10-2", "8/4"]
