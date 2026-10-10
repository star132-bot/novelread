# MKread 云书库

App 通过云书库自动获取新书和更新。书以 [MKBook](formats/mkbook-v1.md) 包发布，App 下载后走和本地导入完全相同的校验。

## 架构

```
App ──HTTPS──► Cloudflare ──tunnel──► 应用服务器
                                       └─ mkread-library (Docker, 127.0.0.1:8095)
                                            ├─ 书籍文件  /srv/mkread-library
                                            └─ PostgreSQL ──SSL──► 数据库服务器 / mkread_library
登录：App → 书库服务器 → MKauth (OIDC)
```

- 书库服务器是 MKauth 的**机密客户端**，client secret 只存在服务器上。
- App 登录后拿到书库自己的**设备令牌**（180 天，使用时自动续期），不接触 MKauth 令牌。
- App 联网时每 6 小时同步一次，也可以在「云端书库」页手动同步。新书进入「云书库」书架文件夹；同一本书的新版本原地更新，按章节 id 保留阅读进度。
- 下架的书不会从已下载的设备上删除，只是不再更新。

## 服务器文件

| 位置 | 内容 |
|---|---|
| 应用服务器 `/opt/mkread-library/compose.yaml` | 运行配置（来自 `server/deploy/compose.yaml`） |
| 应用服务器 `/opt/mkread-library/.env` | 数据库连接、MKauth 客户端、管理令牌（600 权限，不入库；模板见 `server/.env.example`） |
| 应用服务器 `/opt/mkread-library/src/` | 构建镜像用的源码 |
| 应用服务器 `/srv/mkread-library/` | 书籍包和封面 |
| 数据库服务器 | 数据库 `mkread_library`，账号 `mkread_library_app`，pg_hba 只放行应用服务器（hostssl） |

具体主机和凭据位置记录在不入库的运维文档里。

## 一次性配置

1. **Cloudflare**：Zero Trust → Networks → Tunnels → 阿里云2 上的隧道 → Public Hostname：
   `books.mkauth.sbs` → `http://localhost:8095`
2. **MKauth**：新建 Web（机密）应用，回调 `https://books.mkauth.sbs/api/v1/auth/callback`，范围 `openid profile email`。
   然后在服务器上填入：
   ```bash
   # 在应用服务器上
   nano /opt/mkread-library/.env      # 填 OIDC_CLIENT_ID 和 OIDC_CLIENT_SECRET
   cd /opt/mkread-library && docker compose up -d
   ```
3. **第一位管理员**：用自己的 MKauth 账号打开 `https://books.mkauth.sbs/admin/` 登录一次（会提示没有权限，但账号已经建档），然后在应用服务器上授予超级管理员：
   ```bash
   cd /opt/mkread-library
   docker compose exec -T api python -m app.accounts grant <你的邮箱或 subject> superadmin --reason "首位管理员"
   docker compose exec -T api python -m app.accounts admins     # 查看所有管理员
   ```
   之后的管理员直接在管理平台「用户与账号」里分配角色。也可以把 subject 写进 `ADMIN_SUBJECTS`（永远是超级管理员），或在 MKauth 给账号加 `mkread:viewer` / `mkread:operator` / `mkread:admin` 角色。

## 发布书

```bash
python3 tools/mkbook/mkbook.py build 书名.mktxt -o 书名.mkbook                      # 首次
python3 tools/mkbook/mkbook.py build 书名.mktxt --previous 书名.mkbook -o 书名-r2.mkbook  # 更新
```

然后任选一种上传：

- 管理平台：「书籍 → 上传书籍」，选文件并填写原因。
- 命令行：`curl -H "Authorization: Bearer <管理令牌>" -F file=@书名.mkbook "https://books.mkauth.sbs/api/v1/books?reason=首发"`

同一本书再次上传时 `revision` 必须更大；校验不通过会返回具体原因。

## 管理平台

`https://books.mkauth.sbs/admin/`，用 MKauth 登录（复用书库已有的回调地址，不需要在 MKauth 另外配置）。

| 角色 | 权限 |
|---|---|
| 只读管理员 `viewer` | 查看所有页面、导出 CSV |
| 运营 `operator` | 另外可以上传、下架、恢复书籍；停用、启用用户；吊销设备会话；调整配额 |
| 超级管理员 `superadmin` | 另外可以分配角色；撤回、恢复 App 版本；设置强制更新 |

有效角色取「平台分配」「MKauth 授予」「`ADMIN_SUBJECTS`」三者中最高的一个。停用账号后，该账号的 App 和管理会话立即失效；所有修改都写入审计日志（操作人、时间、原因、改前改后）。

**管理接口**（`/api/v1/admin/*`，接口文档在 `/admin/api-docs`，只对管理员开放）：

- 认证方式：
  - 管理平台的 Cookie 会话：HttpOnly，8 小时有效。写请求必须带 `X-MKread-Admin: 1` 头。
  - App 设备令牌：账号需要有管理角色。
  - `ADMIN_API_TOKEN`：给脚本用。
- 列表接口：
  - 参数：`page`（从 1 开始）、`page_size`（不超过 200）、`sort`（字段名，前加 `-` 表示降序）、`q`，以及各资源自己的筛选参数。
  - 返回：`{items, page, page_size, total}`。
  - 加 `format=csv` 可导出，最多 1 万行。
- 写接口必须带 `reason`。
- 错误统一返回 `{code, message, details}`。
- 资源：`overview`、`search`、`users`、`books`（含 `batch`）、`voice-packs`、`releases`、`audit`、`session`。

App 用的数据接口（`/api/v1/catalog`、`/books`、`/voices`、`/app`）不变，和管理接口分开。其他应用（笔记、文件）接入时，按同样的接口规范各自实现管理接口。

## 音色包

APK 只内置「标准女声」（Matcha）。高音质（MeloTTS）、多音色（Kokoro）、声音克隆（ZipVoice）是可下载的音色包：

- `GET /api/v1/voices` 列出音色包（无需登录），下载地址形如 `/api/v1/voices/files/<id>-r<revision>-<sha前16位>.zip`，内容永不变化，Cloudflare 会缓存。
- 包格式：ZIP 内含 `pack.json`（id、revision、每个文件的 size 和 SHA-256）和按安装路径存放的文件；服务端发布时、App 安装时都会逐个文件校验。
- 包的 `revision` 必须与 App 里 `VoiceModel.revision` 一致；改模型或合成参数时两边一起升级。

在应用服务器上（国内服务器经 GitHub 镜像下载，所有文件仍按 `speech-assets.lock.json` 校验）：

```bash
cd /opt/mkread-library/voice-build        # 含 scripts/ 和 speech-assets.lock.json
MKREAD_ASSET_MIRROR=https://ghfast.top/ ./scripts/fetch-speech-assets.sh
cp .local-assets/voice-packs/*.zip /srv/mkread-library/incoming/ && chown 10001:10001 /srv/mkread-library/incoming/*.zip
cd /opt/mkread-library && docker compose exec -T api sh -c "python -m app.voices publish /data/incoming/*.zip"
# 然后在本机运行 scripts/sync-github-mirror.sh，把新音色包放到国内下载镜像（见下节）
```

## 国内下载镜像

Cloudflare 在国内很慢（实测约 85 KB/s），所以 APK 和音色包另有一份国内能快速下载的镜像。`/api/v1/voices` 和 `/api/v1/app/latest` 对镜像发 HEAD（不跟随跳转；文件存在的结果缓存 10 分钟，不存在时 30 秒后重查，刚发布的文件很快就会改走镜像）：文件存在且大小一致就下发镜像地址，否则仍给 Cloudflare 地址；App 两种地址都校验大小和 SHA-256，App 端无需改动。镜像上的文件名与服务器一致：`mkread-<版本>-<versionCode>-<sha前16位>.apk`、`<id>-r<revision>-<sha前16位>.zip`，内容永不变化。

**当前：GitHub Release + 免费代理。** 文件放在本仓库的 `downloads` Release（预发布，不影响 Latest），国内经 `https://gh-proxy.com/`（备用 `https://ghfast.top/`）代理下载。这些是第三方服务，随时可能失效：`DOWNLOAD_MIRROR_URL` 可以用逗号写多个，服务器按顺序选第一个能拿到文件的；连不上的代理会被跳过 2 分钟，全部失效时退回 Cloudflare。

- `.env`：`DOWNLOAD_MIRROR_URL=https://gh-proxy.com/https://github.com/<owner>/<repo>/releases/download/downloads,https://ghfast.top/https://github.com/<owner>/<repo>/releases/download/downloads`，重启 api 生效。可在应用服务器上用 `curl -sI <代理>/https://github.com/...apk` 检查代理是否可用（需返回 200 和正确的 content-length，不能是跳转）。
- 同步：在能快速访问 GitHub、已登录 `gh` 的机器上运行 `scripts/sync-github-mirror.sh`（已有的跳过；`dist/` 或 `$MKREAD_MIRROR_SOURCES` 里哈希一致的文件直接用，否则从书库下载）。`scripts/publish-release.sh` 发版后会自动执行；发布音色包后手动运行一次。

**备选：阿里云 OSS（上海，按下载流量计费约 0.5 元/GB，更稳更快）。** 建桶（华东 2，公共读，关闭「阻止公共访问」，用默认域名，无需备案）；建只能写这个桶的 RAM 子账号，密钥在应用服务器上用 `ossutil config` 录入，不进仓库也不进 `.env`；`DOWNLOAD_MIRROR_URL=https://<bucket>.oss-cn-shanghai.aliyuncs.com`；同步用 `/opt/mkread-library/sync-download-mirror.sh`（仓库里是 `server/deploy/sync-download-mirror.sh`，走内网上传）。

本地开发想把所有音色打进 APK：`./gradlew :app:assembleDebug -Pmkread.bundleAllVoices=true`。

## Release 签名

在 `~/.gradle/gradle.properties`（不入库）里配置：

```properties
mkread.signing.storeFile=/path/to/mkread-release.jks
mkread.signing.storePassword=...
mkread.signing.keyAlias=mkread
mkread.signing.keyPassword=...
```

没有配置时 `assembleRelease` 产出未签名包。

## App 版本发布与应用内更新

App 启动时（最多每 12 小时一次）以及「更多选项 → 检查更新」会请求 `GET /api/v1/app/latest?current=<versionCode>`（无需登录）。有新版本时弹窗提示，用户点「立即更新」后 App 在内部下载 APK（校验大小和 SHA-256），交给系统安装器，用户确认一次即可覆盖安装，数据全部保留。`min_supported` 大于当前版本时为强制更新（弹窗不能关闭）。

前提：所有版本必须用同一个 release 签名密钥（见上文「Release 签名」）；首次更新时 Android 会要求用户允许 MKread「安装未知应用」。

发布一个版本（versionCode 必须递增）：

```bash
MKREAD_APP_SERVER=<应用服务器 ssh 主机> scripts/publish-release.sh 0.3.1 4 "本次更新内容"
```

脚本会构建签名包、创建 GitHub Release 并附上 APK，然后让应用服务器经镜像拉取、校验并登记。撤回某个版本：在应用服务器上 `docker compose exec -T api python -m app.releases withdraw --version-code 4`。

## 更新服务端

```bash
# 在仓库根目录
COPYFILE_DISABLE=1 tar --no-xattrs -czf - server/Dockerfile server/requirements.txt server/app server/deploy tools/mkbook/mkbook.py \
  | ssh <应用服务器> 'cd /opt/mkread-library/src && tar -xzf -'
ssh <应用服务器> 'cd /opt/mkread-library/src && docker build -f server/Dockerfile \
  --build-arg PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ -t mkread-library:1.0.0 . \
  && cd /opt/mkread-library && docker compose up -d'
```

（应用服务器在国内、跨境带宽很低，所以在服务器上用国内镜像构建，而不是从本机推镜像。）

## 本地开发

```bash
ADMIN_API_TOKEN=$(openssl rand -hex 32) READ_ACCESS=public docker compose -f server/docker-compose.dev.yml up --build
# 模拟器里把「云端书库地址」设为 http://10.0.2.2:8090（仅 debug 包允许 http）
TEST_DATABASE_URL=postgresql://mkread:mkread-dev@127.0.0.1:55432/mkread python3 -m unittest discover server/tests
```
