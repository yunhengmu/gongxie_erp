FROM python:3.12-slim

WORKDIR /app

# 安装 uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

# 复制依赖文件
COPY pyproject.toml uv.lock ./

# 安装核心依赖（不含 GPU 版 embedding）
RUN uv sync --no-dev --frozen

# 复制源码
COPY . .

# 服务端口
EXPOSE 8000

# 启动
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]