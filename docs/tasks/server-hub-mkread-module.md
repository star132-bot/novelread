# 任务：在 Server Hub 里嵌入 MKread 数据管理

交给 Codex（Server Hub 的维护者）。目标：在 Server Hub（hub.mkauth.sbs）侧栏点「MKread 数据」，就能在页面里直接管理 MKread 的用户、书籍、音色、版本和审计。

方案：MKread 的管理界面已经写好并部署。Server Hub 只需要换取一个一次性链接，然后用 iframe 打开它。**不需要重写页面**，预计约 30 行代码。

## MKread 侧已经完成（不要改）

- **一次性链接接口**：`POST http://mkread-library:8000/api/v1/admin/embed-tickets`
  - 请求头：
    - `Authorization: Bearer <服务令牌>`
    - `X-Admin-Actor: <操作人>`：建议用 Server Hub 的 `req.auth.username`，需匹配 `^[A-Za-z0-9._@:+-]{1,128}$`
    - `X-Admin-Actor-Name: <encodeURIComponent(显示名)>`
  - 请求体（可选）：`{"theme": "dark" | "light" | "system", "path": "/overview"}`
    - `path` 可选 `/overview`、`/books`、`/users`、`/releases`、`/audit`、`/voices`
  - 返回：`{"url": "https://books.mkauth.sbs/admin/embed?ticket=...", "expires_in": 60}`
    - 链接 60 秒内有效，只能用一次。打开后得到一个 8 小时的会话，身份是 `server-hub:<操作人>`，角色是超级管理员。
    - 所有修改都写入 MKread 审计日志，记到这个人名下。
- **嵌入后的界面**：书库只允许 `https://hub.mkauth.sbs` 用 iframe 嵌入（CSP `frame-ancestors`）。嵌入状态下，界面不显示自己的登录、退出和主题切换，主题跟随传入的 `theme`。会话过期时提示「请在 Server Hub 里重新打开」。
- **服务令牌**：在应用服务器的 `/root/mkread-library/server-hub-service-token`，权限 600。**不要打印、不要提交、不要发给浏览器。**
- **内网**：Docker 网络 `platform-admin` 已经建好，书库在里面的别名是 `mkread-library`。

## Server Hub 侧要做的

### 1. 后端：一个接口
新增 `POST /api/v1/mkread/embed`，挂在 `authRoutes` 之后，这样会自动要求登录并校验 CSRF。

```ts
// apps/api/mkread.ts
import { Router } from "express";
import { readFileSync } from "node:fs";
import { ApiError, audit, type AuthRequest, type Context } from "./context.js";

export function mkreadRoutes(ctx: Context) {
  const router = Router();
  const base = process.env.MKREAD_ADMIN_URL || "http://mkread-library:8000";
  const tokenFile = process.env.MKREAD_ADMIN_TOKEN_FILE;
  router.get("/mkread/config", (_req, res) => res.json({ enabled: Boolean(tokenFile) }));
  router.post("/mkread/embed", async (req: AuthRequest, res) => {
    if (!tokenFile) throw new ApiError(404, "未配置 MKread", "MKREAD_DISABLED");
    const token = readFileSync(tokenFile, "utf8").trim();
    const { theme = "system", path = "/overview" } = req.body ?? {};
    const response = await fetch(`${base}/api/v1/admin/embed-tickets`, {
      method: "POST",
      signal: AbortSignal.timeout(10_000),
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        "X-Admin-Actor": req.auth!.username,
        "X-Admin-Actor-Name": encodeURIComponent(req.auth!.displayName || req.auth!.username),
      },
      body: JSON.stringify({ theme, path }),
    }).catch(() => null);
    if (!response?.ok) throw new ApiError(503, "MKread 书库暂时不可用", "MKREAD_UNAVAILABLE");
    await audit(ctx, req, "mkread.open", "mkread");
    res.json(await response.json()); // { url, expires_in }
  });
  return router;
}
```
在 `app.ts` 的 `/api/v1` 路由列表里加上 `mkreadRoutes(ctx)`。如果 `express` 的异步错误不会自动传给错误处理，按项目现有写法处理。

### 2. CSP
在 `app.ts` 的 helmet 配置里加一条：`"frame-src": ["'self'", "https://books.mkauth.sbs"]`。

### 3. 前端：一个页面
- `labels` 里加 `mkread: "MKread 数据"`，图标用 `BookOutlined` 或 lucide 的 `Library`。只有 `GET /api/v1/mkread/config` 返回 `enabled` 时才显示这个菜单。
- 进入页面时调用 `api("/mkread/embed", "POST", { theme: themeMode, path: "/overview" })`，把返回的 `url` 填到 iframe 的 `src`：
  ```tsx
  {page === "mkread" && (mkreadUrl
    ? <iframe className="mkread-frame" src={mkreadUrl} title="MKread 数据管理" />
    : <div className="center"><Spin /></div>)}
  ```
  ```css
  .mkread-frame { width: 100%; height: 100%; min-height: calc(100vh - 64px); border: 0; display: block; }
  ```
  页面要占满工作区，参照「满屏管理工作区」的做法，不要外层边距。
- 每次进入页面都重新取链接，因为链接只能用一次。Server Hub 切换主题时也重新取一次，好让主题一致。
- 取链接失败时，在页面里显示「MKread 书库暂时不可用」和重试按钮，不要影响其他页面。

### 4. 部署
- `deploy/compose.yaml`：
  - app 加入外部网络 `platform-admin`（`external: true`），同时保留默认网络。
  - 环境变量：
    - `MKREAD_ADMIN_URL=http://mkread-library:8000`
    - `MKREAD_ADMIN_TOKEN_FILE=/run/secrets/mkread_admin_token`（或项目习惯的路径）
- 令牌：把 `/root/mkread-library/server-hub-service-token` 复制到 Server Hub 的私密目录，属主 1000:1000、权限 400，只读挂载进容器。
- 按 Server Hub 自己的发布流程：先备份，保留旧镜像。

## 验收
1. 登录 Server Hub 后，侧栏出现「MKread 数据」。点开后在页面里看到 MKread 概览，不需要再登录。
2. 在嵌入页面里调整一个测试用户的配额（必须填写原因）。然后到嵌入页面的「审计日志」查看：操作人应为「你的名字（server-hub）」。Server Hub 自己的审计里也有一条 `mkread.open`。
3. 浏览器开发者工具里看不到服务令牌。直接访问 `https://books.mkauth.sbs/admin/embed?ticket=随便填`，应显示「链接已失效」。
4. 切换 Server Hub 的深色和浅色主题后，重新进入页面，主题一致。
5. 停掉书库容器时，页面显示「暂时不可用」，Server Hub 其他功能正常。
6. 在 1366 和 390 像素宽度下检查布局。

## 不要做
- 不要让浏览器直接持有服务令牌，也不要把令牌放进 URL。
- 不要直接连接 `mkread_library` 数据库。
- 不要修改书库服务的代码和配置。需要新功能的话，写进本仓库 `docs/HANDOFF.md`，由 Claude 实现。
