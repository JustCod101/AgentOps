# AgentOps CLI

Python Click CLI for the AgentOps multi-agent AIOps platform.

## 安装

### 开发模式（可编辑）
```bash
cd cli
pip install -e .
```

### 生产模式
```bash
cd cli
pip install .
```

安装后命令为 `agentops`。

## 卸载

```bash
pip uninstall agentops-cli
```

## 配置

登录并保存 API key：
```bash
agentops login --api-key <your-api-key> --save
```

配置会保存在 `~/.agentops/config.toml`。

## 使用

```bash
# 查看帮助
agentops --help

# 登录
agentops login --api-key <your-key> --save

# 发起诊断（流式输出）
agentops diagnose "数据库响应变慢" --stream

# 查看历史
agentops history

# 查看会话详情
agentops session <session-id>
```

## API 端点配置

默认连接 `http://localhost:8080`，可通过环境变量覆盖：

```bash
export AGENTOPS_API_URL=http://your-server:8080
agentops diagnose "问题描述"
```
