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
3. **管理员**：用自己的 MKauth 账号在 App 里登录一次，然后把自己的 subject 写进 `ADMIN_SUBJECTS`（或在 MKauth 给账号加 `mkread:admin` 角色）。查 subject：
   ```bash
   # 在数据库服务器上
   sudo -u postgres psql -d mkread_library -c 'select subject, display_name, created_at from device_sessions order by created_at desc limit 5'

   ```

## 发布书

```bash
python3 tools/mkbook/mkbook.py build 书名.mktxt -o 书名.mkbook                      # 首次
python3 tools/mkbook/mkbook.py build 书名.mktxt --previous 书名.mkbook -o 书名-r2.mkbook  # 更新
```

然后任选一种上传：

- 网页：打开 `https://books.mkauth.sbs/`，填管理令牌（应用服务器 `.env` 里的 `ADMIN_API_TOKEN`），选文件上传。
- 命令行：`curl -H "Authorization: Bearer <管理令牌>" -F file=@书名.mkbook https://books.mkauth.sbs/api/v1/books`

同一本书再次上传时 `revision` 必须更大；校验不通过会返回具体原因。

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
