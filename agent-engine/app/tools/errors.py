class ToolGatewayError(Exception):
    """工具层预期内异常，携带稳定错误码供 SSE 和审计使用。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
