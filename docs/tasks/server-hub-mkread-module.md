# 任务：把 MKread 数据管理并入 Server Hub

交给 Codex（Server Hub 的维护者）。目标：用户在 **Server Hub**（hub.mkauth.sbs）里就能管理 MKread 的用户、书籍、音色和版本，不必再打开另一个后台。

## 已经完成的部分（MKread 侧）

- 书库服务提供管理接口 `/api/v1/admin/*`。规范见 `docs/cloud-library.md` 的「管理平台」一节；OpenAPI 在 `https://books.mkauth.sbs/admin/api-docs`，需要管理员登录。
  - 列表：`page`、`page_size`、`sort`（字段名，前加 `-` 表示降序）、`q` 和各资源的筛选参数，返回 `{items, page, page_size, total}`；带 `format=csv` 时导出 CSV。
  - 写操作必须带 `reason`。错误统一为 `{code, message, details}`，HTTP 状态码按语义返回。
  - 资源：`session`、`overview`、`search`、`users`、`users/{sub}`、`users/{sub}/revoke-sessions`、`books`、`books/{id}`、`books/{id}/unpublish|restore`、`books/batch`、`POST books`（multipart：`file` 和 `reason`）、`voice-packs`、`releases`、`releases/{code}/withdraw|restore`、`PATCH releases/{code}`、`audit`。
- **服务令牌**：Server Hub 后端用它代表当前登录的人调用上面的接口。
  - 每个请求都要带：
    - `Authorization: Bearer <令牌>`
    - `X-Admin-Actor: <操作人标识>`，需匹配 `^[A-Za-z0-9._@:+-]{1,128}$`
    - 可选 `X-Admin-Actor-Name: <encodeURIComponent(显示名)>`
  - 书库审计日志记为 `server-hub:<操作人>`。令牌的角色是 `superadmin`。
  - 令牌文件在应用服务器的 `/root/mkread-library/server-hub-service-token`，权限 600。**不要打印、不要提交、不要发给浏览器。**
- **内网地址**：应用服务器上已创建 Docker 网络 `platform-admin`，书库容器在这个网络里的别名是 `mkread-library`。Server Hub 的 app 容器加入该网络后，可以直接访问 `http://mkread-library:8000`，不经过 Cloudflare。
- 书库自带的独立后台 `https://books.mkauth.sbs/admin/` 先保留作为备用。它的交互和文案可以当参考实现，源码在 `server/app/admin_ui/app.js`。

## 需要做的部分（Server Hub 侧）

### 1. 后端代理 `apps/api/mkread.ts`
- 挂在 `authRoutes` 之后，这样自动要求 Server Hub 会话；写请求还会校验 `X-CSRF-Token`。
- 路由 `/api/v1/mkread/*` 转发到 `${MKREAD_ADMIN_URL}/api/v1/admin/*`：
  - **白名单**：只放行上面列出的资源和方法，路径参数要 `encodeURIComponent`，防止拼出其他 URL（SSRF）。
  - **请求头**：带上 `Authorization`、`X-Admin-Actor`（建议用 `req.auth.username`）、`X-Admin-Actor-Name`。浏览器发来的 `Authorization` 和 `Cookie` 一律不转发。
  - **请求体与查询串**：JSON 原样转发，查询串原样转发。
  - **上传书**：multipart 流式转发，不要被 `express.json` 的 1 MB 上限挡住，大小上限按书库的 200 MB。
  - **封面**：`/api/v1/mkread/covers/:id?r=` 转发到 `${MKREAD_ADMIN_URL}/api/v1/books/:id/cover`。
  - **CSV**：原样透传 `Content-Type` 和 `Content-Disposition`。
  - **超时**：读请求 15 秒，上传 120 秒。书库连不上时返回 503，格式 `{message, code: "MKREAD_UNAVAILABLE"}`。
  - **错误**：书库的错误体 `{code, message}` 和 Server Hub 前端期望的格式兼容，状态码原样返回。
- 每个写请求成功后，再写一条 Server Hub 自己的 `AuditEvent`：`action = mkread.<资源>.<动作>`，`targetType = mkread-<资源>`。
- **配置**（加在 `config.ts`）：
  - `MKREAD_ADMIN_URL`：默认 `http://mkread-library:8000`。
  - `MKREAD_ADMIN_TOKEN_FILE`：参照 `MASTER_KEY_FILE` 的方式只读挂载，容器 UID 1000 要能读。
  - 两个都没配置时，隐藏 MKread 菜单。

### 2. 前端
- **导航**：侧栏新增一组「MKread」，包含：概览、书籍、用户、版本、审计。用户少的话，也可以做成一个「MKread」页面，用 Radix Tabs 分标签。
- **组件**：沿用现有的组件和样式（`@tanstack/react-table`、Radix Dialog / AlertDialog、sonner、lucide 图标），保持满屏工作区布局（见 `docs/design/management-2026-10-01.md`）。
- **页面**：
  - **概览**：用户数、7 日活跃、上架和下架书籍数、存储用量、最新版本、近 14 天活跃和新增、最近操作。
  - **书籍**：
    - 列表：封面、书名作者、ID、版本、章节、字数、大小、状态、发布时间。
    - 支持搜索、状态筛选、排序、分页、CSV、多选批量下架或恢复，以及上传 `.mkbook`。
    - 详情：分卷、前 10 章、版本历史、操作记录；可下架、恢复、下载。
  - **用户**：
    - 列表：名称邮箱、有效角色及来源、状态、有效设备数、最近活跃、注册时间、配额。
    - 支持搜索、状态和角色筛选、排序、分页、CSV、批量停用、启用、吊销会话。
    - 详情：基本信息、设备会话、操作记录；可停用或启用、吊销会话、调整配额、分配角色。
  - **版本**：列表；撤回或恢复，撤回时要求输入版本号确认；设置最低支持版本（强制更新）。
  - **审计**：书库审计日志，可按操作人、操作、对象、日期筛选；详情显示改前和改后。
- **交互约定**：
  - 所有写操作都弹出确认框，必须填写原因（2–200 字）。危险操作的按钮用红色。
  - 停用账号要输入名字确认，撤回版本要输入版本号确认。
- **状态处理**：加载中用骨架屏；空数据给出说明；出错时显示错误码和重试按钮；书库不可用时显示提示，不要让整个 Server Hub 报错。
- 全局搜索（Quick Finder）可以接入 `GET /api/v1/mkread/search?q=`，结果包括用户和书籍。

### 3. 部署
- `deploy/compose.yaml` 的 app 加入外部网络 `platform-admin`（已存在，`external: true`）。默认网络保留。
- 令牌：把 `/root/mkread-library/server-hub-service-token` 复制到 Server Hub 的私密目录，权限 400、属主 1000:1000，在 `deploy/.env` 里配置 `MKREAD_ADMIN_TOKEN_FILE`。
- 按 Server Hub 自己的发布流程：先备份数据库和源码，保留旧镜像。

## 验收
1. 在 Server Hub 登录后能看到 MKread 菜单。概览、书籍、用户、版本、审计的数据和书库后台一致。
2. 在 Server Hub 里停用一个测试用户：该用户的 App 立即返回 401；书库审计记为 `server-hub:<你的用户名>`；Server Hub 自己也有一条 `AuditEvent`。
3. 上传、下架、恢复一本测试书，App 端同步结果正确。完成后删除测试数据（下架即可）。
4. 浏览器网络面板里看不到服务令牌；直接请求 `/api/v1/mkread/../../xxx` 之类的路径会被拒绝。
5. 停掉书库容器时，MKread 页面显示「书库暂时不可用」，Server Hub 其他功能正常。
6. 在 1680、1366、768、390 像素宽度下截图检查，深色和浅色主题都要看。

## 不要做
- 不要让浏览器直接访问书库接口，也不要放宽 CSP 的 `connect-src`。
- 不要直接连接 `mkread_library` 数据库，所有数据都走 API。
- 不要修改书库服务的代码和配置。需要新接口的话，在本仓库提需求（写进 `docs/HANDOFF.md`），由 Claude 实现。
