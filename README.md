# travel-agent-guide（Java / Spring Boot 3）

商旅 AI Agent 后端demo,涵盖：意图识别、ReAct 工具循环、多路 RAG 召回、Redis 短期记忆、Milvus 向量侧、OpenAI 兼容接口与 SSE 流式输出。

## 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 8、Redis 7（本地或 Docker）
- Milvus 2.x（可选；未启动时向量通道自动降级）
- OpenAI 兼容 API Key（`OPENAI_API_KEY`）

## 快速开始

1. 复制并编辑环境变量（可在 shell 或 IDE Run Configuration 中设置）：

```bash
export OPENAI_API_KEY=sk-...
export MYSQL_HOST=localhost
export MYSQL_USER=root
export MYSQL_PASSWORD=travelagent
export REDIS_HOST=localhost
export MILVUS_HOST=localhost
```

2. 创建数据库（名称与 `application.yml` 中 `MYSQL_DATABASE` 一致，默认 `travel_agent`）：

```sql
CREATE DATABASE travel_agent DEFAULT CHARACTER SET utf8mb4;
```

3. 编译并运行：

```bash
mvn clean package -DskipTests
java -jar target/travel-agent-guide-1.0.0-SNAPSHOT.jar
```

4. 健康检查：

```bash
curl -s http://localhost:8080/api/v1/health
```

5. 非流式对话：

```bash
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","message":"帮我查一下一线城市酒店差标"}'
```

6. SSE 流式（`text/event-stream`，超时 120s）：

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

将启动 MySQL、Redis、Milvus（嵌入式单机镜像）及应用镜像（需先 `mvn package` 构建 jar 并由 Dockerfile 打包）。详见 `docker-compose.yml` 内注释。

## 配置说明

主要配置见 `src/main/resources/application.yml`：

| 前缀 | 含义 |
|------|------|
| `spring.datasource.*` | MySQL |
| `spring.data.redis.*` | Redis 会话记忆 |
| `spring.ai.openai.*` | OpenAI 兼容 API |
| `travel.agent.*` | Agent 行为、RAG、Milvus、MCP、熔断 |

## 模块结构

- `controller`：REST + SSE
- `core`：编排、ReAct、意图、RAG、记忆、工具、提示词状态机
- `domain`：商旅领域模型
- `infrastructure`：模型路由、熔断、追踪切面
- `etl`：文档入库流水线

## 许可证

示例工程代码，按项目需要自行补充测试与合规审计。
