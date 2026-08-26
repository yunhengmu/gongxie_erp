"""统一响应模型 —— 类比 Spring 项目中的 Result<T> / AjaxResult。

设计参考：
  - 若依 (RuoYi) AjaxResult：code + msg + data
  - 阿里巴巴 Java 开发手册：统一返回格式
  - FastAPI 最佳实践：Pydantic GenericModel + 全局异常处理

用法示例：
  # 成功返回
  return ApiResponse.success(data=ChatResponse(...))
  return ApiResponse.success(data=ChatResponse(...), message="操作成功")

  # 错误返回
  return ApiResponse.error(code=400, message="参数校验失败")
  return ApiResponse.not_found("用户不存在")

  # 分页返回
  return PageResponse.success(data=PageInfo(page=1, page_size=10, total=100, items=[...]))
"""

from typing import Generic, TypeVar, Optional, Any
from pydantic import BaseModel, Field
from enum import IntEnum


# ==================== 业务状态码 ====================

class StatusCode(IntEnum):
    """业务状态码枚举。

    与 HTTP 状态码保持一致，便于前端统一处理。
    """
    SUCCESS = 200            # 请求成功
    CREATED = 201            # 创建成功
    BAD_REQUEST = 400        # 参数错误 / 业务校验失败
    UNAUTHORIZED = 401       # 未认证
    FORBIDDEN = 403          # 无权限
    NOT_FOUND = 404          # 资源不存在
    CONFLICT = 409           # 资源冲突
    UNPROCESSABLE = 422      # 语义错误
    INTERNAL_ERROR = 500     # 服务器内部错误
    SERVICE_UNAVAILABLE = 503  # 服务不可用


# ==================== 泛型类型变量 ====================

T = TypeVar("T")


# ==================== 统一响应体 ====================

class ApiResponse(BaseModel, Generic[T]):
    """统一 API 响应体。

    Attributes:
        code: 业务状态码，默认 200
        message: 提示信息，默认 "success"
        data: 响应数据，泛型，可为 None
    """
    code: int = Field(default=StatusCode.SUCCESS, description="业务状态码")
    message: str = Field(default="success", description="提示信息")
    data: Optional[T] = Field(default=None, description="响应数据")

    # ---------- 成功工厂方法 ----------

    @classmethod
    def success(
        cls,
        data: T = None,
        message: str = "success",
    ) -> "ApiResponse[T]":
        """返回成功响应。"""
        return cls(code=StatusCode.SUCCESS, message=message, data=data)

    @classmethod
    def created(
        cls,
        data: T = None,
        message: str = "创建成功",
    ) -> "ApiResponse[T]":
        """返回创建成功响应（201）。"""
        return cls(code=StatusCode.CREATED, message=message, data=data)

    # ---------- 错误工厂方法 ----------

    @classmethod
    def error(
        cls,
        code: int = StatusCode.INTERNAL_ERROR,
        message: str = "服务器内部错误",
        data: T = None,
    ) -> "ApiResponse[T]":
        """返回通用错误响应。"""
        return cls(code=code, message=message, data=data)

    @classmethod
    def bad_request(cls, message: str = "请求参数错误") -> "ApiResponse":
        """返回参数错误响应（400）。"""
        return cls(code=StatusCode.BAD_REQUEST, message=message, data=None)

    @classmethod
    def unauthorized(cls, message: str = "未登录或 Token 已过期") -> "ApiResponse":
        """返回未认证响应（401）。"""
        return cls(code=StatusCode.UNAUTHORIZED, message=message, data=None)

    @classmethod
    def forbidden(cls, message: str = "无操作权限") -> "ApiResponse":
        """返回无权限响应（403）。"""
        return cls(code=StatusCode.FORBIDDEN, message=message, data=None)

    @classmethod
    def not_found(cls, message: str = "资源不存在") -> "ApiResponse":
        """返回资源不存在响应（404）。"""
        return cls(code=StatusCode.NOT_FOUND, message=message, data=None)

    @classmethod
    def internal_error(cls, message: str = "服务器内部错误") -> "ApiResponse":
        """返回服务器内部错误响应（500）。"""
        return cls(code=StatusCode.INTERNAL_ERROR, message=message, data=None)


# ==================== 分页模型 ====================

class PageInfo(BaseModel, Generic[T]):
    """分页数据模型。

    Attributes:
        page: 当前页码
        page_size: 每页条数
        total: 总记录数
        items: 数据列表
    """
    page: int = Field(default=1, ge=1, description="当前页码")
    page_size: int = Field(default=10, ge=1, le=100, description="每页条数")
    total: int = Field(default=0, ge=0, description="总记录数")
    items: list[T] = Field(default_factory=list, description="数据列表")

    @property
    def total_pages(self) -> int:
        """总页数。"""
        if self.page_size == 0:
            return 0
        return (self.total + self.page_size - 1) // self.page_size


class PageResponse(ApiResponse[PageInfo[T]], Generic[T]):
    """分页响应体 —— 继承 ApiResponse，data 固定为 PageInfo。

    用法：
        return PageResponse.success(
            data=PageInfo(page=1, page_size=10, total=100, items=[...])
        )
    """
    pass