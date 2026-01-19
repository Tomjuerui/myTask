# Electron + Spring Boot AI 桌面端

## 目录结构

```
electron/
├─ src/main/  main.js  preload.js  api.js
├─ src/renderer/  React 组件 + Markdown 渲染
└─ assets/  icon.icns  icon.ico
backend/
├─ src/main/java/com/example/app/controller/AskController.java
├─ src/main/java/com/example/app/service/LLMService.java
├─ src/main/java/com/example/app/service/ToolService.java
└─ src/main/resources/application.yml
```

## 运行步骤

### 1. 启动后端

```bash
cd backend
export SPRING_PROFILES_ACTIVE=secret
export AI_API_KEY=your_openai_key
export SEARCH_API_KEY=your_bing_key
mvn spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/api/health
```

### 2. 启动 Electron

```bash
cd electron
npm install
npm run dev:renderer
# 另开一个终端
npm run dev
```

默认会通过 `BACKEND_URL=http://localhost:8080` 访问后端，可通过环境变量覆盖：

```bash
export BACKEND_URL=http://localhost:8080
export BACKEND_AUTH_TOKEN=your_token
export BACKEND_API_KEY=your_api_key
```

## 打包步骤

### 1. 打包前端资源

```bash
cd electron
npm run build:renderer
```

### 2. 打包桌面客户端

```bash
npm run pack
```

### 3. 打包后端

```bash
cd backend
mvn package
```

## Function Calling 演示

可在桌面端输入：

> “今天北京的天气如何？请给出参考来源。”

服务会先触发 `search_web` 工具检索，再通过 SSE 流式推送回答。 
