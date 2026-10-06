# 任务：剩余项目的数据统一（交给 Codex）

背景：用户希望所有项目的数据都统一放在日本数据库服务器（`mk-jp-db`，PostgreSQL 17），由管理平台统一管理。主机别名和登录方式在维护者自己的运维资料里，本仓库不写地址。

## 已经在 mk-jp-db 上的
mkauth、chatmk、chatmk_legacy、sub2api、filehub、control_plane、jev_notes、mkread_library、server_hub、zju_agent。

- mk-jp-db 已有每日逻辑备份：`pg-nightly-backup.timer`，每天北京时间 03:30，存放在 `/var/backups/postgres`，保留 8 天。
- 新迁过去的库会自动纳入备份。

## 2026-10-03 只读排查发现、还没统一的

| 主机 | 项目 | 当前数据位置 | 建议 |
|---|---|---|---|
| mk-aly | `affectlive-backend.service`（/opt/affectlive） | SQLite `/opt/affectlive/data/affectlive.sqlite3` | 先确认还在用；在用就改成 PostgreSQL 并迁到 mk-jp-db，不用就归档后停用 |
| mk-aly | `file-transfer.service`（/opt/file-transfer） | SQLite `/opt/file-transfer/data/files.sqlite3`，文件存本机 | 同上；文件本身不放进数据库 |
| mk-aly | `resource-admin.service`（/opt/resource-download-site） | SQLite `/opt/resource-download-site/data/resources.db` | 同上 |
| A_server | `mt-cijian.service`（/opt/mt-cijian） | SQLite `/opt/mt-cijian/mt_cijian.db`（240K） | 同上 |
| A_server | `mt-presence`、`mt-presence-scanner` | 外部 Supabase（托管 PostgreSQL） | 和用户确认是否迁回自有数据库。异地备份目前失败，见下文 |
| mk-jp01 | `1panel-postgresql-main`（运行中） | 1Panel 自带的 PostgreSQL | 查清楚哪些应用在用。sub2api 的数据库连接写在它的配置文件里，没出现在环境变量中，需要确认用的是这个库还是 mk-jp-db 的 `sub2api` 库；如果 mk-jp-db 上那个是旧副本，要标清楚 |
| mk-aly2 | MKauth 阿里云节点 | 应在 mk-jp-db 的 `mkauth` 库 | 确认一下即可（Spring 的数据源变量名和其他项目不同，这次排查没有识别出来） |

## 迁移通用要求（和 zju-agent、Server Hub 的做法一致）

1. **建库**：在 mk-jp-db 为每个应用建一个独立的库和同名 `<app>_app` 角色。
   - 密码存在 `/root/<app>/db_password`，权限 600。
   - `pg_hba` 加一条 hostssl 规则，限定库、角色和来源 IP；改前先备份 `pg_hba.conf`。
   - ufw 只放行应用服务器。
2. **SQLite 迁到 PostgreSQL**：需要改应用代码或 ORM 配置。先在测试环境跑通，对比每张表的行数，再停写切换。
3. **切换**：停写 → 最终导出 → 导入 → 逐表核对行数 → 改连接配置（先备份）→ 启动 → 验证网站和 `pg_stat_activity`。
4. **回滚**：旧数据保留 24 小时以上；归档到 `mk-jp-db:/srv/archive/<日期>/`，校验后才能删除源数据。
5. 不打印 `.env` 和密码；不改 MKauth 本身。

## 其他待办
- **10-04 13:00 之后**：把 mk-aly 上 `zju-agent-db`、mk-aly2 上 `server-hub-db` 的数据卷归档到 mk-jp-db，然后删除这两个容器和卷。届时回滚窗口结束，两个应用已经在用 mk-jp-db。
- **mt-presence 异地备份**：A_server 的 Tailscale 自 9-18 起停用，用户选择改走公网，服务器侧已经改好。还差用户在阿里云控制台给阿里云 1 的安全组放行 A_server 访问 TCP 22。放行后执行 `systemctl start mt-presence-offsite-backup`，再执行 `mt-presence-offsite-verify` 确认。
- **mk-jp-db 备份的异地副本**：目前每日备份只存在本机。
