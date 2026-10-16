# workbuddy-cloud-worker

> 把 WorkBuddy 的「每日签到 / 猫猫旅行 / 成长计划任务 / 令牌续期」搬到云上跑，**电脑关不关机都不影响**。

一个零外部依赖（Node ≥ 18）的单进程 HTTP 服务：启动后自己按间隔循环干活，同时暴露几个管理端点供你查看状态。

---

## 它做什么

| 能力 | 说明 |
|---|---|
| **每日签到** | 自动领「Buddy 加油站」当日积分（幂等：已领不重复请求） |
| **猫猫旅行循环** | 遍历你名下的所有猫：`切换 → 查状态 → 到了就领奖 → 空闲就派出`，跑完**恢复你原来选中的那只** |
| **成长计划任务** | 自动接取 + 补缺口 + 领奖；覆盖 15 条常驻任务（见下） |
| **令牌自我续期** | access 剩 <7 天时自动用 refresh 换新（**官方每次刷新都会轮换 refresh，本服务是唯一链主**） |
| **运行历史（可选）** | 若配置了云服务，每次真实动作落库，`/history` 可查 |

**成长计划已覆盖的任务：** 设计创意模式画布、探索灵感、资料库、腾讯轻量云专家、发现应用、桌面端对话、尝鲜技能、召唤 5 次专家、**召唤 3 次专家团**、使用 5 个模板、和 AI 聊天 5 次、设置自动化任务。

> ⚠️ 「体验和平精英主题」默认**跳过**（换主题对客户端有真实副作用）。如需开启，删除 `growth-tasks.js` 里的 `SKIP_TASKS` 项。

---

## 快速开始

### 1. 准备配置

```bash
cp config.example.json config.json
```

编辑 `config.json`：

```jsonc
{
  "access_key": "自己随便生成一串随机字符",   // 保护管理端点，别用默认值
  "accounts": [
    {
      "name": "我的账号",
      "uid": "用户ID",                          // 可选
      "accessToken": "eyJ...",                  // 必填
      "refreshToken": "...",                    // 强烈建议填
      "expiresAt": 0,
      "refreshExpiresAt": 0
    }
  ]
}
```

**怎么拿到 access / refresh 令牌？** 登录 WorkBuddy 客户端后，登录态是加密存在本地的；也可以用官方刷新接口自行获取。本仓库**不提供**令牌提取脚本 —— 请用你自己的方式获取，并妥善保管。

### 2. 运行

```bash
node server.js                 # 默认 8080 端口
PORT=3000 node server.js       # 指定端口
TICK_MINUTES=20 node server.js # 指定循环间隔（分钟）
```

启动后会立刻先跑一轮，然后按 `TICK_MINUTES` 循环。

### 3. 查看状态

```bash
curl http://127.0.0.1:8080/health                      # 免鉴权
curl "http://127.0.0.1:8080/status?key=<access_key>"    # 完整状态
curl "http://127.0.0.1:8080/log?key=<access_key>"       # 最近日志
```

---

## 接口速查

| 端点 | 鉴权 | 用途 |
|---|---|---|
| `GET /health` | 无 | 存活、uptime、tickCount、各账号令牌剩余天数 |
| `GET /status?key=` | 有 | 完整状态（签到、猫猫、次数） |
| `GET /log?key=` | 有 | 最近运行日志 |
| `GET /tokens?key=` | 有 | 最新 access 令牌（供本机同步，不含 refresh） |
| `GET /config?key=` | 有 | 完整配置导出（**含 refresh，别外传**） |
| `GET /history?key=&limit=` | 有 | 云数据库运行历史 |
| `POST /tick?key=` | 有 | 立即执行一轮 |
| `POST /refresh?key=` | 有 | 立即检查续期 |

---

## 部署到「平台托管」（可选）

本服务天然适合跑在**单端口 HTTP 托管**上（监听 `$PORT`、绑定 `0.0.0.0`）：

1. 把本目录（含 `config.json`）发布成一个 Node HTTP 服务；
2. 平台会注入 `PORT` 环境变量，服务自动适配；
3. 沙箱类环境通常会被周期回收重启 —— 没关系，**文件系统跨重启持久**（轮换后的令牌不丢），且**重启即自动跑一轮**。

> 💡 若平台支持「保活」定时任务，建议每小时访问一次 `/health`，防止冷启动被冻结。

---

## 原理（想自己改代码看这里）

### 成长任务的进度是怎么判定的？

**遥测埋点。** 任务进度不依赖"真的用了某个功能"，而是服务端读取客户端上报的遥测事件：

```
POST https://www.workbuddy.cn/v2/report
Body: [ { eventCode: "xxx", userId, machineId, sessionId, ... } ]
```

不同任务对应不同 `eventCode`（详见 [`docs/成长任务机制.md`](docs/成长任务机制.md)）。

### 两条关键坑

1. **chat 类任务必须发"真实对话"** —— 光发埋点不算数。要先建会话（`/console/webchat/conversations`），再真调一次 `/console/chat/completions`，最后补三连埋点。
2. **「召唤专家团」必须带 `_meta` 发起真实团队对话** —— 这是它和「召唤专家」最大的区别：
   - 普通专家：发 `expert_summoned` + `expert_actual_use` 即可；
   - 专家团：必须在 `/console/chat/completions` 请求体里加 `_meta["codebuddy.ai"].growthEvent`（内嵌 `ExpertActualUse`、`expertType: "team"`），**服务端在对话完成时才记账**。

### 令牌链（最容易踩的坑）

- access 30 天 / refresh 60 天，刷新接口：`POST /v2/plugin/auth/token/refresh`（refresh 放在 `X-Refresh-Token` 头）。
- 🔴 **每次刷新都返回轮换后的新 refresh，旧 refresh 立即作废** ⇒ **链主只能有一个**。若你用本服务的云端续期，就别再用别的脚本拿同一个 refresh 去刷。
- 🔴 **重新部署前必须先 `GET /config` 回拉云端最新配置**，否则用旧 refresh 覆盖沙箱会把整条链打断。

---

## ⚠️ 风险与免责（务必读）

1. **活动规则随时会变。** 签到额度、任务清单、奖励数值、接口形状都可能被官方调整；本服务对此没有任何保证。
2. **官方活动细则明确写着：刷量、多账号套利会回收奖励并限制账号。** 本仓库只是把"人在客户端点一遍"的流程自动化，**是否使用、后果如何，由使用者自行判断和承担**。
3. **令牌等于你的账号。** `config.json` 一旦泄露，等于账号被接管 —— 请确保它**永不进入版本库、永不上传到任何公开位置**（本仓库的 `.gitignore` 已默认忽略）。
4. 本项目**仅供学习与技术研究**，不构成对任何平台服务条款的豁免建议。

---

## 文件结构

```
.
├── server.js              # 主服务：签到 / 猫猫旅行 / 续期 / 调度 / HTTP 端点
├── growth-tasks.js        # 成长任务配方（每个任务一段埋点逻辑）
├── config.example.json    # 配置模板
├── package.json
├── docs/
│   └── 成长任务机制.md
└── LICENSE
```

## License

MIT
