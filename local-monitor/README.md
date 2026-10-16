# WorkBuddy Multi-Account Monitor

一个隐私优先、完全本地运行的 WorkBuddy 多账号工具，包含电脑端管理面板、Android App、桌面小组件、自动签到和用量统计。

> 非官方社区项目，与腾讯或 WorkBuddy 官方无隶属关系。请在遵守服务条款和当地法律的前提下使用。

> 📦 **本目录现为 [workbuddy-cloud-worker](https://github.com/interli3/workbuddy-cloud-worker) 仓库的 `local-monitor/` 子目录**（原仓库 `interli3/workbuddy-multi-account-monitor` 已并入）。
> 本目录的文档与命令若出现 `git clone .../workbuddy-multi-account-monitor.git`、`cd workbuddy-multi-account-monitor` 等字样，请把仓库地址换成上面的新仓库，并在克隆后进入 `local-monitor/` 目录再执行。
> 同一仓库的**云端自动化**部分见 [`../cloud-worker/`](../cloud-worker/README.md)。

![WorkBuddy Monitor 脱敏演示界面](docs/app-overview-demo.png)

上图为 AI 生成的视觉演示图，使用虚构数据，并非实际 App 截图；实际界面以安装后的版本为准。

## 能做什么

- 动态管理任意数量的 CN / Global 账号，不写死账号名称或数量。
- 汇总余额、套餐周期、今日消耗、本月消耗和任务流水。
- 对 CN 账号执行幂等每日签到，已签到会安全跳过。
- Windows 09:00、21:00 与登录后补签；执行前自动验证并同步所有已导入账号的本地登录态。
- Android App 提供概览、账号、记录、健康度和风险提醒（深色玻璃主题）。
- App 内新增 **DeepSeek 余额**页（第 4 个标签页）：手机**直连 DeepSeek 官方余额接口**，**电脑关机也能查**；与 WorkBuddy 积分各用一套偏好存储、采样历史、闹钟与通知渠道，两本账互不干扰。
- 桌面小组件底部多一行 DeepSeek 余额与今日消耗（蓝紫色，与 WorkBuddy 的青蓝一眼区分）。
- DeepSeek 的「消耗」由两次采样之间的余额下降推算（官方没有用量接口）；样本跨度不足 6 小时时明确显示「样本不足」，不做线性外推的假精度。
- Android 桌面小组件显示余额、签到和任务状态。
- 局域网 HTTP 优先，外网可选 MQTT 会合点。
- 登录令牌只保存在本机，不写入 APK。

## 设计原则

- **本地优先**：凭证池、用量历史与会话数据全部留在用户电脑。
- **安全默认值**：首次配置只启用带随机令牌保护的局域网；公共 MQTT 必须由用户明确开启。
- **数据驱动**：App 从 Snapshot 动态渲染当前和未来账号。
- **不伪造成功**：过期数据会明确标记，编译通过不等于真机验收完成。

## 系统结构

```text
WorkBuddy 本机登录状态 / 本地数据库
                 │
                 ▼
      Node.js 多账号面板与签到工具
                 │
                 ▼
         Python 伴侣服务 :8791
             │          │
             │          └── MQTT（可选外网会合点）
             ▼
       Android App / Widget
```

## 界面介绍

![安卓 App 界面地图](docs/app-screens.svg)

> 上图为**界面结构示意**（虚构数据），用来说明每个页面有什么；实际界面以安装后的版本为准。

### 安卓 App —— 4 个标签页

**顶部状态栏（所有页面共用）**

- 左边一个状态点 + 文字：`正在拉取…` / `已连接` / `电脑离线 · 显示最近快照`
- 接着是 `更新于 …`（相对时间：刚刚 / N 分钟前 / N 小时前）
- 右边三个按钮：**隐私**（一键隐藏敏感数字）、**刷新**、**设置**

**① 概览 —— 一屏回答"情况怎么样"**

| 区块 | 显示什么 |
| :-- | :-- |
| 剩余可用 | 所有账号的总剩余积分（大字） |
| 今日消耗 | 当天消耗；右上角标 `云端` / `本地`，说明这个数从哪来 |
| 近 7 天使用趋势 | 迷你折线图 |
| 账户健康 | 评分 + `为什么是这个分 ›` —— 给出扣分原因，不凭空丢一个数字 |
| 需要关注 | 告警卡：余额偏低 / 凭证已过期 / 已禁用 |
| 当前任务 | `N 个进行中`，或 `当前没有正在执行的任务` |

**② 账户 —— 逐账号明细**

- 每个账号一张卡：区域标签（`CN` / `GLB`）、余额、`N 个套餐`、`周期剩 xx%`、`xx 重置`
- 点卡片展开详情
- 空状态明确写 `还没有账号，请在电脑端添加`（不假装有数据）

**③ 记录 —— 发生了什么**

- **最近任务**：任务流水（运行中 / 已完成，以及各自用了多少积分）
- **最近积分变动**：收入和消耗分开列，例如 `+100 Buddy 加油站` / `-128 任务消耗`
- 空状态：`暂无积分变动记录`

**④ DeepSeek —— 独立的一本账**

- 页首一行小字先把关系说清楚：`独立于 WorkBuddy 积分 · 手机直连 DeepSeek 官方余额接口`（不经过电脑）
- 余额 / 今日消耗 / 近 7 天消耗 / 余额走势 / 最近消耗
- 空状态：`暂无消耗记录。余额一旦下降就会被记为一次消耗；如果余额长期不动，属于正常情况。`
- 右上角一个刷新键，可立刻取一次数

> 🔵🟣 **颜色即语义**：WorkBuddy 相关一律**青蓝**，DeepSeek 相关一律**蓝紫**。两本账即使同屏也一眼分得开 —— 它们各自有独立的偏好存储、采样历史、闹钟与通知渠道，互不干扰。

### 设置页（数据源设置）

- **伴侣服务地址**：分两栏 —— `公网地址`（人在外面走这个）、`局域网地址`（在家同一 WiFi 走这个，更快）
- **访问令牌**（可留空）
- **采样间隔**：5 / 10 / 15 / 30 分、1 时（**15 分钟为推荐值**；间隔越短越准，代价是耗电与流量）
- **余额预警**：低于阈值发通知，回升后重新武装；可发送一条测试通知
- **桌面小组件**：说明入口（长按桌面 → 小组件 → 用量中心）
- **采样数据**：清空采样历史

### 桌面小组件（Android）

- 两种尺寸：**小尺寸**、**中尺寸**
- 显示：总剩余积分、今日签到状态、进行中任务数
- **最下面一行**是 DeepSeek 余额与今日消耗（蓝紫色），与上面的 WorkBuddy 内容各算各的

### 电脑端 Web 面板（`npm start` 后打开 http://127.0.0.1:8765）

- **顶部动作**：`导入本机账号`、`扫码登录新账号`、`刷新数据`、`全部签到`、`全部领取(Global)`、`会话迁移`、`+ 添加凭证`
- **筛选**：`全部` / `CN` / `Global` / `耗尽`
- **账号卡片**：区域标签、`今日已签`、禁用原因；开关 —— `自动签到(CN)`、`耗尽自动关`、`耗尽自动删`
- **折叠面板**：`添加新账号`（拉取当前客户端账号）、`跨账号会话迁移`（选源账号 → 目标账号 → `迁移选中` / `导出选中`）
- 另有浏览器小组件 `public/widget.html`

## 五分钟开始使用

### 1. 准备环境

基础功能需要：

- Windows 10/11、macOS 或 Linux
- Node.js 18+
- Python 3.10+
- 已安装并登录 WorkBuddy

当前 APK 构建脚本仅支持 Windows，还需要：

- JDK 17（设置 `JAVA_HOME`）
- Android SDK Platform 34
- Android Build Tools 34 或更高版本
- 设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`

### 2. 克隆并初始化

```bash
git clone https://github.com/interli3/workbuddy-multi-account-monitor.git
cd workbuddy-multi-account-monitor
python scripts/configure.py
```

初始化脚本会自动探测局域网地址、生成独立的伴侣服务令牌，并创建不会提交到 Git 的本地配置。若识别了错误网卡，可执行：

```bash
python scripts/configure.py --lan-ip 192.168.1.100
```

默认不会向公共 MQTT broker 发送任何数据。如果确实需要手机离开局域网后继续查看，可主动开启，并自动生成独立随机主题：

```bash
python scripts/configure.py --enable-public-mqtt
```

重复运行配置脚本会保留已有的 MQTT 主题和伴侣服务令牌。若要重新关闭 MQTT：

```bash
python scripts/configure.py --disable-mqtt
```

### 3. 导入账号

```bash
npm start
```

浏览器打开 [http://127.0.0.1:8765](http://127.0.0.1:8765)，点击“导入本机账号”。添加多个账号时，在 WorkBuddy 中切换并登录目标账号，再次导入即可；账号按 UID 合并。

### 4. 启动伴侣服务

```bash
python scripts/run_bridge.py
```

验证地址：

- [http://127.0.0.1:8791/health](http://127.0.0.1:8791/health)
- [http://127.0.0.1:8791/snapshot](http://127.0.0.1:8791/snapshot)
- [http://127.0.0.1:8791/status](http://127.0.0.1:8791/status)

### 5. 构建 Android App

```powershell
python mobile/build_apk.py --clean
```

输出为 `mobile/out/wbmon.apk`。首次构建会在本机生成独立签名密钥和随机密码；请自行备份，丢失后无法覆盖升级原 APK。自己配置后构建的 APK 已内置本机局域网地址和伴侣服务令牌，但不会包含 WorkBuddy 登录令牌。

如果使用 GitHub Release 中不绑定任何电脑的通用 APK，它不会包含维护者的地址、令牌或 MQTT 主题；请先在电脑运行 `python scripts/configure.py`，再把 App 设置中的“局域网地址”和“伴侣服务令牌”分别填写为 `config.local.json` 的 `lanUrl` 与 `bridgeToken`。维护者本人的个人绑定 APK 不应上传到 GitHub；本仓库源码与公开通用 APK 的包名统一为 `io.github.workbuddymonitor`。

### 6. 安装 Windows 自动签到

```powershell
powershell -ExecutionPolicy Bypass -File scripts/install_windows_checkin_task.ps1
```

计划任务在每天 09:00、21:00 和用户登录 Windows 后运行；失败每 15 分钟重试，最多三次，并避免重叠执行。

## 常用命令

```bash
node src/cli.js import-local     # 导入当前 WorkBuddy 登录账号
node src/cli.js sync-auth        # 在线验证并同步已导入账号的本地登录态快照
node src/cli.js list             # 查看账号列表，不显示 token
node src/cli.js refresh-all      # 刷新额度并记录用量
node src/cli.js checkin-all      # 给所有可签到账号签到
python mobile/audit.py           # Android 静态审计
python mobile/test_daymark.py    # 今日消耗回归测试
python mobile/test_usedtoday_fix.py
python mobile/test_bridge_auth.py
```

## 数据口径

- WorkBuddy 的 `working` 状态映射为“运行中”。
- `session_usage.used` 是 Token 数，不能直接当作积分。
- `credit_json` 是会话累计积分快照，今日消耗使用本地午夜前后的累计值求差。
- 签到奖励和赠送积分不会被误算为消费。
- 套餐余额不动但周期已用增加时，周期口径用于避免漏报。

## 目录结构

```text
src/                    Node.js 面板、账号池、签到和用量逻辑
public/                 本机管理面板与浏览器小组件
mobile/java/            原生 Android Java 源码
mobile/res/             Android 布局与视觉资源
mobile/bridge_multi.py  Python 多账号伴侣服务
mobile/build_apk.py     零 Gradle APK 构建脚本
scripts/configure.py    本地隐私配置生成器
scripts/run_bridge.py   伴侣服务入口
scripts/*.ps1           Windows 自动签到脚本
docs/                   已脱敏公开素材
```

## 安全与隐私

绝对不要提交 `credentials.json`、WorkBuddy `*.info` 文件、JWT、数据库、日志、会话导出、Android 签名密钥或 `config.local.json`。

非本机访问 `/snapshot`、`/status` 和 `/access` 时，伴侣服务会校验随机 Bearer 令牌；`/health` 与 APK 下载保持公开。公共 MQTT 默认关闭。主动启用后使用的是匿名 broker；随机主题降低碰撞和猜测概率，但不等于加密。敏感环境建议保持局域网模式，或换成自己控制的认证 MQTT broker。详见 [SECURITY.md](SECURITY.md)。

## 登录授权说明

WorkBuddy 官方访问令牌不是永久凭证。本项目不会绕过官方认证，也不会把 token 嵌入 APK。计划任务会扫描 WorkBuddy 当前及历史登录态，但只采用经官方额度接口验证仍有效的候选，避免“到期时间更晚但已被撤销”的旧 token 覆盖可用账号。官方撤销全部会话、修改密码或所有候选真正失效时，仍需在 WorkBuddy 中重新登录对应账号并再次导入。

## 发布前测试

CI 模板保存在 `docs/ci-workflow.yml`。将它放到 `.github/workflows/ci.yml` 后可启用 GitHub Actions；当前发布账号授权不含 workflow 权限，因此本次仅完成本地测试，未运行远端 CI。

```bash
npm run audit
npm test
python scripts/configure.py --no-auto-lan --no-bridge-auth --disable-mqtt
python mobile/build_apk.py --clean
```

上面的三个配置开关只用于制作不含维护者地址、令牌或 MQTT 主题的公开通用 APK。项目包含布局资源、Widget RemoteViews 白名单、今日消耗、跨日重置和局域网鉴权测试。需要验证可选公共 MQTT 时，另行运行 `node mobile/_mqtt_probe.js`。真实手机安装、触控和桌面组件仍应在目标设备上最终验收。

## 贡献

欢迎提交 Issue 和 Pull Request。截图、日志和测试数据必须先脱敏。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## License

[MIT](LICENSE)
