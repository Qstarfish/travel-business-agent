# travel-agent-guide (Java / Spring Boot 3)

商旅 AI Agent 后端 demo，包含：

- 意图识别
- ReAct 工具调用循环
- 多路 RAG 召回
- Redis 短期会话记忆
- PostgreSQL / pgvector 向量检索
- OpenAI 兼容接口
- SSE 流式输出

## 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8
- Redis 7
- PostgreSQL 16 + pgvector
- OpenAI 兼容 API Key

## 快速开始

1. 配置环境变量

```bash
export OPENAI_API_KEY=sk-...
export MYSQL_HOST=localhost
export MYSQL_USER=root
export MYSQL_PASSWORD=travelagent
export REDIS_HOST=localhost
export PGVECTOR_HOST=localhost
export PGVECTOR_PORT=5432
export PGVECTOR_DATABASE=travel_agent_vector
export PGVECTOR_USER=postgres
export PGVECTOR_PASSWORD=postgres
```

2. 初始化数据库

```sql
CREATE DATABASE travel_agent DEFAULT CHARACTER SET utf8mb4;
```

PostgreSQL / pgvector 可执行：

```sql
CREATE DATABASE travel_agent_vector;
\c travel_agent_vector
CREATE EXTENSION IF NOT EXISTS vector;
```

也可以直接使用 [`pgvector-init.sql`](D:/CodeWorkspace/travel-agent-guide-main/project-java/src/main/resources/sql/pgvector-init.sql)。

3. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/travel-agent-guide-1.0.0-SNAPSHOT.jar
```

4. 健康检查

```bash
curl -s http://localhost:8080/api/v1/health
```

5. 非流式对话

```bash
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","message":"帮我查一下一线城市酒店差标"}'
```

6. SSE 流式对话

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","message":"预订下周上海往返机票需要注意什么"}'
```

## Docker Compose

在项目根目录执行：

```bash
docker compose up -d
```

会启动 MySQL、Redis、PostgreSQL/pgvector 和应用服务。

## 核心配置

见 [`application.yml`](D:/CodeWorkspace/travel-agent-guide-main/project-java/src/main/resources/application.yml)：

- `spring.datasource.*`：MySQL 业务库
- `spring.data.redis.*`：Redis 会话记忆
- `spring.ai.openai.*`：Chat + Embedding 模型
- `travel.agent.pgvector.*`：pgvector 连接与向量维度
- `travel.agent.rag.*`：检索 TopK 与重排配置

## 模块结构

- `controller`：REST + SSE
- `core`：编排、ReAct、意图、RAG、记忆、工具、Prompt 状态机
- `domain`：商旅领域模型
- `infrastructure`：模型路由、embedding、pgvector、链路追踪
- `etl`：文档分块与向量入库
