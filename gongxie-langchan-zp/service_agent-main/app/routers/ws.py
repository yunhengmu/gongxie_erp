"""WebSocket 路由 — 双向实时通信。

支持:
  - 流式问答（服务端主动推送 token）
  - 对话状态同步
  - 心跳检测

协议: WebSocket (RFC 6455)
"""
import json
import logging
import uuid
from typing import Optional

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends

from app.dependencies import get_rag_service
from app.services.rag_service import RAGService

logger = logging.getLogger(__name__)

router = APIRouter(tags=["ws"])


class WSConnectionManager:
    """WebSocket 连接管理器。"""

    def __init__(self):
        self._connections: dict[str, WebSocket] = {}

    async def connect(self, client_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        self._connections[client_id] = websocket
        logger.info("WS 连接: %s (total=%d)", client_id, len(self._connections))

    def disconnect(self, client_id: str) -> None:
        self._connections.pop(client_id, None)
        logger.info("WS 断开: %s (total=%d)", client_id, len(self._connections))

    async def send(self, client_id: str, message: dict) -> bool:
        """发送 JSON 消息到指定客户端。"""
        ws = self._connections.get(client_id)
        if ws:
            try:
                await ws.send_json(message)
                return True
            except Exception:
                self.disconnect(client_id)
        return False

    async def broadcast(self, message: dict) -> None:
        """广播消息到所有客户端。"""
        disconnected = []
        for cid, ws in self._connections.items():
            try:
                await ws.send_json(message)
            except Exception:
                disconnected.append(cid)
        for cid in disconnected:
            self.disconnect(cid)


manager = WSConnectionManager()


@router.websocket("/ws/chat/{client_id}")
async def websocket_chat(
    websocket: WebSocket,
    client_id: str,
):
    """WebSocket 聊天端点。

    消息格式:
      发送: {"type": "question", "content": "...", "thread_id": ""}
      接收: {"type": "token", "content": "..."}
            {"type": "done", "thread_id": "..."}
            {"type": "error", "message": "..."}
            {"type": "ping"} / {"type": "pong"}  (心跳)
    """
    await manager.connect(client_id, websocket)
    thread_id = ""
    rag_service = None  # 延迟初始化

    try:
        # 尝试获取 RAG service
        from app.dependencies import get_rag_service_sync
        rag_service = get_rag_service_sync()

        while True:
            data = await websocket.receive_text()
            try:
                message = json.loads(data)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "message": "Invalid JSON"})
                continue

            msg_type = message.get("type", "")

            # 心跳处理
            if msg_type == "ping":
                await websocket.send_json({"type": "pong"})
                continue

            # 问答处理
            if msg_type == "question":
                question = message.get("content", "")
                thread_id = message.get("thread_id", "") or f"ws-{uuid.uuid4().hex[:8]}"

                if not question:
                    await websocket.send_json({"type": "error", "message": "content is required"})
                    continue

                # 流式推送答案
                if rag_service:
                    async for token in rag_service.ask_stream(
                        question=question,
                        thread_id=thread_id,
                    ):
                        await websocket.send_json({"type": "token", "content": token})

                await websocket.send_json({"type": "done", "thread_id": thread_id})

            else:
                await websocket.send_json({"type": "error", "message": f"Unknown type: {msg_type}"})

    except WebSocketDisconnect:
        logger.info("WS 客户端断开: %s", client_id)
    except Exception as e:
        logger.exception("WS 异常: %s", e)
        try:
            await websocket.send_json({"type": "error", "message": str(e)})
        except Exception:
            pass
    finally:
        manager.disconnect(client_id)


@router.websocket("/ws/broadcast")
async def websocket_broadcast(websocket: WebSocket):
    """WebSocket 广播端点 — 接收消息并广播给所有客户端。"""
    client_id = f"broadcast-{uuid.uuid4().hex[:6]}"
    await manager.connect(client_id, websocket)

    try:
        while True:
            data = await websocket.receive_text()
            await manager.broadcast({"type": "broadcast", "content": data, "from": client_id})
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(client_id)