import ast
import operator
from collections.abc import Awaitable, Callable
from datetime import datetime
from decimal import Decimal, DivisionByZero, InvalidOperation
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.sandbox.executor import SandboxExecutor, SandboxRejected, STATUS_REJECTED
from app.tools.errors import ToolGatewayError
from app.tools.schemas import ToolDescriptor

# 沙箱执行器（进程隔离 + 资源限额），惰性创建
_SANDBOX: SandboxExecutor | None = None


def _sandbox() -> SandboxExecutor:
    global _SANDBOX
    if _SANDBOX is None:
        _SANDBOX = SandboxExecutor(timeout_seconds=3.0)
    return _SANDBOX

BuiltinHandler = Callable[[dict[str, Any]], Awaitable[Any]]


async def calculator(arguments: dict[str, Any]) -> dict[str, str]:
    expression = str(arguments["expression"])
    value = _SafeMathEvaluator().evaluate(expression)
    return {"expression": expression, "value": _decimal_text(value)}


async def get_current_time(arguments: dict[str, Any]) -> dict[str, str]:
    timezone_name = str(arguments.get("timezone", "Asia/Shanghai"))
    try:
        now = datetime.now(ZoneInfo(timezone_name))
    except ZoneInfoNotFoundError as exc:
        raise ToolGatewayError("INVALID_TIMEZONE", f"未知时区: {timezone_name}") from exc
    return {
        "timezone": timezone_name,
        "iso": now.isoformat(timespec="seconds"),
        "display": now.strftime("%Y-%m-%d %H:%M:%S %Z"),
    }


# ---------------------------------------------------------------------------
# unit_converter：长度/重量/温度/面积换算。基准单位换算 + 温度偏移量特殊处理。
# ---------------------------------------------------------------------------

_UNIT_CATEGORY: dict[str, str] = {
    # length
    "mm": "length", "cm": "length", "dm": "length", "m": "length", "km": "length",
    "inch": "length", "ft": "length", "mile": "length",
    # weight
    "mg": "weight", "g": "weight", "kg": "weight", "t": "weight",
    "jin": "weight", "lb": "weight", "oz": "weight",
    # area
    "m2": "area", "km2": "area", "ha": "area", "mu": "area", "sqft": "area",
    # temperature
    "celsius": "temperature", "fahrenheit": "temperature",
}

_TO_BASE: dict[str, float] = {
    "mm": 0.001, "cm": 0.01, "dm": 0.1, "m": 1.0, "km": 1000.0,
    "inch": 0.0254, "ft": 0.3048, "mile": 1609.344,
    "mg": 1e-6, "g": 0.001, "kg": 1.0, "t": 1000.0,
    "jin": 0.5, "lb": 0.45359237, "oz": 0.028349523125,
    "m2": 1.0, "km2": 1e6, "ha": 10000.0, "mu": 2000.0 / 3.0, "sqft": 0.09290304,
}


async def unit_converter(arguments: dict[str, Any]) -> dict[str, Any]:
    raw_value = arguments.get("value")
    try:
        value = float(raw_value)
    except (TypeError, ValueError):
        raise ToolGatewayError("INVALID_ARGUMENT", "value 必须是数值") from None

    from_unit = str(arguments.get("from_unit", "")).strip().lower()
    to_unit = str(arguments.get("to_unit", "")).strip().lower()
    if not from_unit or not to_unit:
        raise ToolGatewayError("INVALID_ARGUMENT", "from_unit 和 to_unit 必填")

    from_category = _UNIT_CATEGORY.get(from_unit)
    to_category = _UNIT_CATEGORY.get(to_unit)
    if from_category is None or to_category is None:
        raise ToolGatewayError("UNSUPPORTED_UNIT", f"不支持的单位: {from_unit} / {to_unit}")
    if from_category != to_category:
        raise ToolGatewayError(
            "INCOMPATIBLE_UNIT",
            f"单位类别不兼容: {from_unit}({from_category}) → {to_unit}({to_category})",
        )
    category = arguments.get("category")
    if category and category not in {from_category, to_category}:
        raise ToolGatewayError(
            "INVALID_ARGUMENT",
            f"category={category} 与单位类别 {from_category} 不一致",
        )

    if from_category == "temperature":
        result = _convert_temperature(value, from_unit, to_unit)
    else:
        result = value * _TO_BASE[from_unit] / _TO_BASE[to_unit]

    result = round(result, 4)
    display = f"{_number_text(value)} {from_unit} = {_number_text(result)} {to_unit}"
    return {
        "value": value,
        "from_unit": from_unit,
        "to_unit": to_unit,
        "result": result,
        "display": display,
    }


def _convert_temperature(value: float, from_unit: str, to_unit: str) -> float:
    if from_unit == to_unit:
        return value
    if from_unit == "celsius":
        return value * 9 / 5 + 32
    if from_unit == "fahrenheit":
        return (value - 32) * 5 / 9
    raise ToolGatewayError("UNSUPPORTED_UNIT", f"不支持的温度单位: {from_unit}") from None


def _number_text(value: float) -> str:
    return str(int(value)) if value.is_integer() else str(value)


async def code_exec(arguments: dict[str, Any]) -> dict[str, Any]:
    """在沙箱中执行 Python 代码（进程隔离 + 资源限额 + AST 预检）。"""
    code = str(arguments.get("code", ""))
    result = await _sandbox().run(code)
    if result["status"] == STATUS_REJECTED:
        raise ToolGatewayError("CODE_REJECTED", result["stderr"] or "代码未通过安全预检")
    return result


async def knowledge_search(arguments: dict[str, Any]) -> dict[str, Any]:
    """在知识库中检索与 query 最相关的文档片段（含溯源）。"""
    from app.knowledge.store import get_store

    query = str(arguments.get("query", ""))
    top_k = int(arguments.get("top_k", 3))
    doc_ids = arguments.get("doc_ids")
    if not isinstance(doc_ids, list):
        doc_ids = None
    if doc_ids == []:
        results = []
    else:
        results = get_store().search(query, top_k, doc_ids=doc_ids)
    return {
        "query": query,
        "count": len(results),
        "results": results,
    }


BUILTIN_HANDLERS: dict[str, BuiltinHandler] = {
    "calculator": calculator,
    "get_current_time": get_current_time,
    "unit_converter": unit_converter,
    "code_exec": code_exec,
    "knowledge_search": knowledge_search,
}


BUILTIN_DESCRIPTORS = [
    ToolDescriptor(
        code="calculator",
        name="计算器",
        description="计算基础算术表达式，支持加减乘除、取模、幂和括号。",
        input_schema={
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": 256,
                    "description": "仅包含数字和算术运算符的表达式",
                }
            },
            "required": ["expression"],
            "additionalProperties": False,
        },
        output_schema={
            "type": "object",
            "properties": {
                "expression": {"type": "string"},
                "value": {"type": "string"},
            },
            "required": ["expression", "value"],
        },
        executor_type="builtin",
        timeout_ms=3_000,
    ),
    ToolDescriptor(
        code="get_current_time",
        name="获取当前时间",
        description="获取指定 IANA 时区的当前时间，默认 Asia/Shanghai。",
        input_schema={
            "type": "object",
            "properties": {
                "timezone": {
                    "type": "string",
                    "default": "Asia/Shanghai",
                    "maxLength": 64,
                    "description": "IANA 时区，例如 Asia/Shanghai",
                }
            },
            "additionalProperties": False,
        },
        output_schema={
            "type": "object",
            "properties": {
                "timezone": {"type": "string"},
                "iso": {"type": "string"},
                "display": {"type": "string"},
            },
            "required": ["timezone", "iso", "display"],
        },
        executor_type="builtin",
        timeout_ms=3_000,
    ),
    ToolDescriptor(
        code="unit_converter",
        name="单位换算",
        description="单位换算：长度/重量/温度/面积，支持常见公制与英制单位。",
        input_schema={
            "type": "object",
            "properties": {
                "value": {"type": "number", "description": "数值"},
                "from_unit": {"type": "string", "description": "源单位标准名"},
                "to_unit": {"type": "string", "description": "目标单位标准名"},
                "category": {
                    "type": "string",
                    "enum": ["length", "weight", "temperature", "area"],
                    "description": "单位歧义时必填",
                },
            },
            "required": ["value", "from_unit", "to_unit"],
            "additionalProperties": False,
        },
        output_schema={
            "type": "object",
            "properties": {
                "value": {"type": "number"},
                "from_unit": {"type": "string"},
                "to_unit": {"type": "string"},
                "result": {"type": "number"},
                "display": {"type": "string"},
            },
            "required": ["value", "from_unit", "to_unit", "result", "display"],
        },
        executor_type="builtin",
        timeout_ms=3_000,
    ),
    ToolDescriptor(
        code="code_exec",
        name="代码执行沙箱",
        description="在受控沙箱中执行 Python 代码（进程隔离、资源限额、仅允许纯计算库）。返回 stdout/stderr/退出码/耗时。",
        input_schema={
            "type": "object",
            "properties": {
                "code": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": 16384,
                    "description": "要执行的 Python 代码，禁止 IO/网络/进程操作",
                }
            },
            "required": ["code"],
            "additionalProperties": False,
        },
        output_schema={
            "type": "object",
            "properties": {
                "status": {"type": "string", "enum": ["ok", "rejected", "timeout", "crash"]},
                "stdout": {"type": "string"},
                "stderr": {"type": "string"},
                "error_code": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                "exit_code": {"anyOf": [{"type": "integer"}, {"type": "null"}]},
                "duration_ms": {"type": "integer"},
            },
            "required": ["status", "stdout", "stderr", "duration_ms"],
        },
        executor_type="builtin",
        timeout_ms=5_000,
    ),
    ToolDescriptor(
        code="knowledge_search",
        name="知识库检索",
        description="在平台知识库中检索与问题最相关的文档片段，返回标题、原文与相似度（可溯源）。",
        input_schema={
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": 512,
                    "description": "检索问题",
                },
                "top_k": {
                    "type": "integer",
                    "default": 3,
                    "minimum": 1,
                    "maximum": 10,
                    "description": "返回片段数",
                },
                "doc_ids": {
                    "type": "array",
                    "items": {"type": "string", "maxLength": 64},
                    "maxItems": 100,
                    "description": "可选的 Agent 绑定文档 ID 白名单",
                },
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        output_schema={
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "count": {"type": "integer"},
                "results": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "doc_id": {"type": "string"},
                            "title": {"type": "string"},
                            "chunk_id": {"type": "string"},
                            "text": {"type": "string"},
                            "score": {"type": "number"},
                        },
                    },
                },
            },
            "required": ["query", "count", "results"],
        },
        executor_type="builtin",
        timeout_ms=3_000,
    ),
]


class _SafeMathEvaluator:
    _binary = {
        ast.Add: operator.add,
        ast.Sub: operator.sub,
        ast.Mult: operator.mul,
        ast.Div: operator.truediv,
        ast.FloorDiv: operator.floordiv,
        ast.Mod: operator.mod,
        ast.Pow: operator.pow,
    }
    _unary = {ast.UAdd: operator.pos, ast.USub: operator.neg}

    def evaluate(self, expression: str) -> Decimal:
        if len(expression) > 256:
            raise ToolGatewayError("INVALID_ARGUMENT", "表达式长度不能超过 256")
        try:
            tree = ast.parse(expression, mode="eval")
            return self._walk(tree.body, depth=0)
        except ToolGatewayError:
            raise
        except (DivisionByZero, ZeroDivisionError) as exc:
            raise ToolGatewayError("INVALID_EXPRESSION", "除数不能为 0") from exc
        except InvalidOperation as exc:
            raise ToolGatewayError("INVALID_EXPRESSION", "表达式中的数值无效") from exc
        except (SyntaxError, ArithmeticError, ValueError) as exc:
            raise ToolGatewayError("INVALID_EXPRESSION", "表达式无法计算") from exc

    def _walk(self, node: ast.AST, depth: int) -> Decimal:
        if depth > 16:
            raise ToolGatewayError("INVALID_EXPRESSION", "表达式嵌套过深")
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return Decimal(str(node.value))
        if isinstance(node, ast.UnaryOp) and type(node.op) in self._unary:
            return self._unary[type(node.op)](self._walk(node.operand, depth + 1))
        if isinstance(node, ast.BinOp) and type(node.op) in self._binary:
            left = self._walk(node.left, depth + 1)
            right = self._walk(node.right, depth + 1)
            if isinstance(node.op, ast.Pow) and abs(right) > 12:
                raise ToolGatewayError("INVALID_EXPRESSION", "幂指数绝对值不能超过 12")
            result = self._binary[type(node.op)](left, right)
            if abs(result) > Decimal("1e100"):
                raise ToolGatewayError("INVALID_EXPRESSION", "计算结果过大")
            return result
        raise ToolGatewayError("INVALID_EXPRESSION", "表达式包含不允许的语法")


def _decimal_text(value: Decimal) -> str:
    text = format(value.normalize(), "f")
    return text.rstrip("0").rstrip(".") if "." in text else text
