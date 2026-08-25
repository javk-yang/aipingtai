from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.tools.schemas import to_camel


class SkillTrigger(BaseModel):
    """触发规则：keyword 命中任一 values；regex 命中 pattern。"""

    type: Literal["keyword", "regex"] = "keyword"
    values: list[str] = Field(default_factory=list)
    pattern: str | None = None


class SkillStep(BaseModel):
    """技能步骤：一次或多次（repeat=list）调用某个工具。"""

    name: str
    tool: str
    args: dict[str, Any] = Field(default_factory=dict)
    repeat: Literal["once", "list"] = "once"


class SkillContent(BaseModel):
    """技能全文（L1 披露）：SKILL.md 形态为主，steps 编排为兼容通道。

    主通道（SKILL.md 提示词技能）：
    - markdown：正文全文（frontmatter 由 Java 解析后作为扁平字段，正文原样保留）
    - allowed_tools：frontmatter 声明的工具白名单，空列表 = 纯提示词技能（工具收空）

    兼容通道（DB content_json 旧技能）：instructions/steps/prompt/logic 步骤编排。
    """

    markdown: str = ""
    allowed_tools: list[str] = Field(default_factory=list)
    instructions: str = ""
    steps: list[SkillStep] = Field(default_factory=list)
    prompt: str = ""
    logic: dict[str, Any] = Field(default_factory=dict)


class SkillDescriptor(BaseModel):
    """Java 注册中心与 Python SkillEngine 共享的技能描述。

    渐进式披露：列表接口 content=None（元数据层），命中后详情接口带全文。
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    id: int | None = None
    code: str = Field(min_length=1, max_length=128)
    name: str = Field(min_length=1, max_length=128)
    description: str = Field(default="", max_length=512)
    triggers: list[SkillTrigger] = Field(default_factory=list)
    content: SkillContent | None = None
    version: str = "1.0.0"
    enabled: bool = True
    builtin: bool = False


class SkillExecutionResult(BaseModel):
    """技能执行结果：内部工具事件与最终回答。"""

    status: Literal["success", "error", "timeout"]
    result: Any = None
    error_code: str | None = None
    error_message: str | None = None
    duration_ms: int = Field(default=0, ge=0)
    step_outputs: list[dict[str, Any]] = Field(default_factory=list)
    tool_events: list[dict[str, Any]] = Field(default_factory=list)
