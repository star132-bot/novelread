# MKauth / Server Hub 排查结果与已做改动（2026-10-03，Claude）

用户已经把 MKauth、Server Hub、Control Plane 交给 Codex 处理。下面是 Claude 停手前的排查结果和**已经在服务器上做的改动**，避免重复处理或者被意外撤销。主机地址见维护者自己的运维资料。

## 已做的改动

| 主机 | 改动 | 回滚 |
|---|---|---|
| mk-aly2 | 拍了快照：`/root/snapshots/20261003T1608/`，包括 `mkauth-platform.tgz`（MKauth 目录，不含 node_modules 和 target）和 `platform.tgz`（server-hub、control-plane、public-edge） | 无需回滚 |
| mk-aly2 | `/opt/mkauth-platform/mkauth-mini/compose.aliyun.yaml`：auth-server 的 `MKAUTH_DATABASE_URL` 加了 `&tcpKeepAlive=true`；新增环境变量 `SPRING_DATASOURCE_HIKARI_KEEPALIVETIME=30000`、`MAXLIFETIME=300000`、`CONNECTIONTIMEOUT=10000`。已执行 `up -d auth-server`，状态 healthy | 恢复 `compose.aliyun.yaml.bak-hikari-*`，再执行 `docker compose -f compose.aliyun.yaml up -d auth-server` |
| mk-jp01 | 停掉了容器 `mkauth-auth`（没有删除）。停之前快照在 `/root/snapshots/20261003T0825/`（`/opt/mkauth` 打包和容器 inspect） | `docker start mkauth-auth` |

## 排查结论

1. **流量路径**：auth、auth-admin、admin、hub、files、books 都经 Cloudflare 隧道 `mankong-cloudflared-tunnel` 进到 mk-aly2，本机端口依次是 9080、9081、4192、3180、4195、8095。
2. **mk-jp01 的 `mkauth-auth`**：
   - 自 9-20 起手动运行，没有处理任何请求。数据库密码已失效（SQLState 28P01），每 15 秒左右重试一次定时任务，24 小时产生约 5700 条警告。所以停掉了。
   - 该机 nginx 的 `auth-oss`、`mankong-admin` 站点仍经 SSH 反向隧道（mk-aly2 的 `auth-relay-tunnel.service`）转到 mk-aly2，目前没有真实流量，是早期方案的残留。
3. **登录慢**：
   - 国内用户经 Cloudflare 免费版，被分到 AMS 等远端节点，每个请求 1.7–2.3 秒；东京和美国约 0.4–0.5 秒。
   - auth-server 在上海，数据库在东京（来回 153ms）。在服务器本机请求 `/oauth2/authorize` 就要 0.34–0.94 秒，服务启动约 2 分钟。
4. **登录失败**：
   - Hikari 报 `Failed to validate connection`（72 小时 23 次）：跨境链路会掐断空闲 TCP 连接。已用上面的保活配置处理，需要观察是否还会出现。
   - Spring Session 报 `IllegalStateException: Session was invalidated`（`RedisSessionRepository.save`）：会话被并发请求同时操作时抛出，用户看到错误页。需要改代码。
   - 隧道日志里有多条 `context canceled`（`/login`、`/oauth2/authorize`），是用户等待太久后浏览器主动断开。
5. **安全与整洁**：
   - `/opt/mkauth-platform/mkauth-mini` 权限是 777。
   - mkauth edge 的 9080、9081 绑定在 0.0.0.0，目前靠阿里云安全组挡住（实测公网不通）。
   - 三个项目都没有 git，也没有远程仓库。用户同意建私有 GitHub 仓库，但这件事还没做。
   - 目录里有大量手工备份的 jar、compose、`.env`，以及 JVM 崩溃日志。

## MKread 侧（Claude 负责，保持不变）
书库管理接口、`ADMIN_SERVICE_TOKENS`、`ADMIN_EMBED_ORIGINS` 和嵌入方案见 `docs/tasks/server-hub-mkread-module.md`。如果 Server Hub 要接入 MKread 数据页面，按那份文档做。
