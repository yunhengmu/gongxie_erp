"""微服务 API 调用工具集。

每个工具封装一个微服务接口，LLM 无法接触任何凭证、URL、鉴权信息。
所有安全校验由微服务侧完成。

设计原则：
  - LLM 只看到工具名称、参数描述和返回结果
  - 鉴权 Token 在内部注入，不暴露给 LLM
  - 微服务地址从 settings 读取，不在工具描述中展示
  - 工具失败返回友好字符串，不抛异常，让 LLM 可继续处理
"""
import logging
import time

import httpx
from langchain.tools import tool

from config.settings import settings

_microservice_logger = logging.getLogger("microservice.call")

# ==================== 微服务基础地址 ====================
# 从 settings 读取，LLM 永远看不到
ORDER_SERVICE_URL = settings.order_service_url or "http://localhost:9001"
INVENTORY_SERVICE_URL = settings.inventory_service_url or "http://localhost:9002"
USER_SERVICE_URL = settings.user_service_url or "http://localhost:9003"
NOTIFICATION_SERVICE_URL = settings.notification_service_url or "http://localhost:9004"

# 内部鉴权 Token（LLM 永远看不到，由 _request 内部注入）
API_TOKEN = settings.internal_api_token or ""


async def _request(method: str, url: str, **kwargs) -> dict:
    """统一的 HTTP 请求封装，集中处理鉴权、超时和错误。

    Args:
        method: HTTP 方法（GET/POST/PUT/DELETE）
        url: 微服务接口地址
        **kwargs: 透传给 httpx 的参数（如 json、params）

    Returns:
        dict: 微服务返回的 JSON 数据

    Raises:
        httpx.HTTPStatusError: 非 2xx 响应
    """
    start_time = time.time()
    _microservice_logger.info("→ %s %s", method, url)

    async with httpx.AsyncClient(timeout=30) as client:
        headers = {
            "Authorization": f"Bearer {API_TOKEN}",
            "Content-Type": "application/json",
        }
        response = await client.request(method, url, headers=headers, **kwargs)

        duration_ms = (time.time() - start_time) * 1000
        _microservice_logger.info(
            "← %s %s | status=%s | duration=%.2fms",
            method,
            url,
            response.status_code,
            duration_ms,
        )

        response.raise_for_status()
        return response.json()


# ==================== 订单服务工具 ====================

@tool
async def create_order(product_id: str, quantity: int) -> str:
    """创建订单。调用订单微服务，传入商品ID和购买数量，返回订单号。

    使用场景：用户要求购买商品、下单时调用。

    Args:
        product_id: 商品ID
        quantity: 购买数量
    """
    try:
        result = await _request(
            "POST",
            f"{ORDER_SERVICE_URL}/api/orders",
            json={"product_id": product_id, "quantity": quantity},
        )
        return f"订单创建成功，订单号: {result.get('order_id')}"
    except Exception as e:
        return f"[订单创建失败] {e}"


@tool
async def query_order(order_id: str) -> str:
    """查询订单详情。调用订单微服务，返回订单状态、商品、金额等信息。

    使用场景：用户询问订单状态、物流信息时调用。

    Args:
        order_id: 订单号
    """
    try:
        result = await _request(
            "GET",
            f"{ORDER_SERVICE_URL}/api/orders/{order_id}",
        )
        return (
            f"订单号: {result.get('order_id')}\n"
            f"状态: {result.get('status')}\n"
            f"金额: {result.get('amount')}\n"
            f"商品: {result.get('product_name')}\n"
            f"创建时间: {result.get('created_at')}"
        )
    except Exception as e:
        return f"[订单查询失败] {e}"


@tool
async def cancel_order(order_id: str) -> str:
    """取消订单。调用订单微服务，取消指定订单。

    使用场景：用户要求取消订单时调用。

    Args:
        order_id: 订单号
    """
    try:
        result = await _request(
            "PUT",
            f"{ORDER_SERVICE_URL}/api/orders/{order_id}/cancel",
        )
        return f"订单 {order_id} 已取消。原因: {result.get('reason', '用户取消')}"
    except Exception as e:
        return f"[订单取消失败] {e}"


# ==================== 库存服务工具 ====================

@tool
async def check_inventory(product_id: str) -> str:
    """查询库存。调用库存微服务，返回指定商品的库存数量。

    使用场景：用户询问商品是否有货时调用。

    Args:
        product_id: 商品ID
    """
    try:
        result = await _request(
            "GET",
            f"{INVENTORY_SERVICE_URL}/api/inventory/{product_id}",
        )
        return f"商品 {product_id} 当前库存: {result.get('stock')} 件"
    except Exception as e:
        return f"[库存查询失败] {e}"


# ==================== 用户服务工具 ====================

@tool
async def query_user_profile(user_id: str) -> str:
    """查询用户信息。调用用户微服务，返回用户的基本信息（姓名、邮箱、角色等）。

    使用场景：用户询问个人信息、账户信息时调用。

    Args:
        user_id: 用户ID
    """
    try:
        result = await _request(
            "GET",
            f"{USER_SERVICE_URL}/api/users/{user_id}",
        )
        return (
            f"用户ID: {result.get('user_id')}\n"
            f"姓名: {result.get('name')}\n"
            f"邮箱: {result.get('email')}\n"
            f"角色: {result.get('role')}"
        )
    except Exception as e:
        return f"[用户查询失败] {e}"


# ==================== 通知服务工具 ====================

@tool
async def send_notification(user_id: str, message: str) -> str:
    """发送通知。调用通知微服务，向指定用户发送消息通知。

    使用场景：用户要求发送通知、提醒时调用。

    Args:
        user_id: 接收通知的用户ID
        message: 通知内容
    """
    try:
        result = await _request(
            "POST",
            f"{NOTIFICATION_SERVICE_URL}/api/notifications",
            json={"user_id": user_id, "message": message},
        )
        return f"通知已发送给用户 {user_id}。消息ID: {result.get('notification_id')}"
    except Exception as e:
        return f"[通知发送失败] {e}"


# ==================== 工具列表 ====================

# 供 agent.py 导入，按业务域分组
api_tools = [
    create_order,
    query_order,
    cancel_order,
    check_inventory,
    query_user_profile,
    send_notification,
]