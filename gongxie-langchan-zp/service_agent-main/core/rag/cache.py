"""Redis 缓存层。

用途:
  - 热点查询结果缓存，减少 LLM 重复调用
  - 对话状态缓存加速
  - 检索结果缓存

策略:
  - LRU 淘汰：Redis 内存满时自动淘汰最少使用的 key
  - TTL 过期：热点问答默认 1 小时过期
  - 降级：Redis 不可用时自动降级为无缓存模式

依赖: redis-py (pip install redis)
"""
import hashlib
import json
import logging
from functools import wraps
from typing import Any, Callable, Optional

from config.settings import settings

logger = logging.getLogger(__name__)


class RedisCache:
    """Redis 缓存客户端。

    用法:
        cache = RedisCache()
        cache.set("key", "value", ttl=3600)
        value = cache.get("key")
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 6379,
        db: int = 0,
        password: str = "",
        prefix: str = "rag:",
    ):
        """
        Args:
            host: Redis 主机
            port: Redis 端口
            db: 数据库编号
            password: 密码
            prefix: key 前缀，避免命名冲突
        """
        self._host = host
        self._port = port
        self._db = db
        self._password = password
        self._prefix = prefix
        self._client = None
        self._available = False

    @property
    def client(self):
        """延迟初始化 Redis 连接。"""
        if self._client is None:
            try:
                import redis

                self._client = redis.Redis(
                    host=self._host,
                    port=self._port,
                    db=self._db,
                    password=self._password or None,
                    decode_responses=True,
                    socket_connect_timeout=3,
                    socket_timeout=3,
                )
                self._client.ping()
                self._available = True
                logger.info("Redis 缓存已连接 (%s:%d)", self._host, self._port)
            except ImportError:
                logger.warning("redis-py 未安装，缓存功能不可用。安装: pip install redis")
                self._available = False
            except Exception as e:
                logger.warning("Redis 连接失败 (%s:%d): %s，降级为无缓存模式", self._host, self._port, e)
                self._available = False
        return self._client

    def _key(self, raw_key: str) -> str:
        """添加前缀。"""
        return f"{self._prefix}{raw_key}"

    def get(self, key: str, default: Any = None) -> Optional[Any]:
        """获取缓存值，自动反序列化 JSON。"""
        if not self._available:
            return default
        try:
            value = self.client.get(self._key(key))
            if value is None:
                return default
            return json.loads(value)
        except Exception as e:
            logger.debug("缓存读取失败: %s", e)
            return default

    def set(self, key: str, value: Any, ttl: int = 3600) -> bool:
        """设置缓存值，自动序列化为 JSON。

        Args:
            key: 缓存键
            value: 任意可 JSON 序列化的值
            ttl: 过期时间（秒），默认 1 小时

        Returns:
            是否设置成功
        """
        if not self._available:
            return False
        try:
            self.client.setex(
                self._key(key),
                ttl,
                json.dumps(value, ensure_ascii=False),
            )
            return True
        except Exception as e:
            logger.debug("缓存写入失败: %s", e)
            return False

    def delete(self, key: str) -> bool:
        """删除缓存。"""
        if not self._available:
            return False
        try:
            self.client.delete(self._key(key))
            return True
        except Exception:
            return False

    def exists(self, key: str) -> bool:
        """检查 key 是否存在。"""
        if not self._available:
            return False
        try:
            return bool(self.client.exists(self._key(key)))
        except Exception:
            return False

    def ttl(self, key: str) -> int:
        """获取剩余过期时间（秒），-1 永不过期，-2 不存在。"""
        if not self._available:
            return -2
        try:
            return self.client.ttl(self._key(key))
        except Exception:
            return -2


# ========== 缓存装饰器 ==========

def cache_result(
    ttl: int = 3600,
    key_prefix: str = "rag",
    cache_instance: Optional[RedisCache] = None,
):
    """缓存函数返回值的装饰器。

    用法:
        @cache_result(ttl=600, key_prefix="search")
        async def search(query: str) -> str:
            ...

    缓存 key 由 key_prefix + 函数名 + 参数 hash 组成。

    Args:
        ttl: 缓存过期时间（秒）
        key_prefix: 缓存 key 前缀
        cache_instance: 指定缓存实例，默认使用全局实例
    """
    def decorator(func: Callable):
        @wraps(func)
        async def async_wrapper(*args, **kwargs):
            cache = cache_instance or _global_cache
            cache_key = _make_cache_key(key_prefix, func.__name__, args, kwargs)

            # 尝试从缓存读取
            cached = cache.get(cache_key)
            if cached is not None:
                logger.debug("缓存命中: %s", cache_key)
                return cached

            # 执行函数并缓存结果
            result = await func(*args, **kwargs)
            if result is not None:
                cache.set(cache_key, result, ttl)
            return result

        @wraps(func)
        def sync_wrapper(*args, **kwargs):
            cache = cache_instance or _global_cache
            cache_key = _make_cache_key(key_prefix, func.__name__, args, kwargs)

            cached = cache.get(cache_key)
            if cached is not None:
                logger.debug("缓存命中: %s", cache_key)
                return cached

            result = func(*args, **kwargs)
            if result is not None:
                cache.set(cache_key, result, ttl)
            return result

        import asyncio
        if asyncio.iscoroutinefunction(func):
            return async_wrapper
        return sync_wrapper

    return decorator


def _make_cache_key(prefix: str, func_name: str, args: tuple, kwargs: dict) -> str:
    """生成缓存 key。"""
    raw = f"{func_name}:{args}:{sorted(kwargs.items())}"
    hash_hex = hashlib.md5(raw.encode()).hexdigest()[:12]
    return f"{prefix}:{hash_hex}"


# ========== 全局实例 ==========

_global_cache = RedisCache(
    host=settings.redis_host,
    port=settings.redis_port,
    db=settings.redis_db,
    password=settings.redis_password,
)


def get_cache() -> RedisCache:
    """获取全局缓存实例。"""
    return _global_cache