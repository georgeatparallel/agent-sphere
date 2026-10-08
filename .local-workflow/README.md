# 本地 CI（.local-workflow）

在本地复刻 `.github/workflows/deploy.yml` 的主流程：构建推送三镜像 → 改 k8s 镜像 tag 并推 main（**默认不打 tag**）。需要版本发布时加 `--tag`：自动递增 `v1.0.NN-alpha`、打包 Chrome 扩展、建 GitHub Release。**GitHub Action 慢时用它替代**；`.env` / `.cache/` / `output/` 已 gitignore。

> 默认 `--no-tag`：本地 CI 与 tag 触发的 Action 会重复构建/改 k8s，故默认只发镜像 + 提交 k8s；`--tag` 才发布版本。

## 为什么要它

`deploy.yml` 只有一个 job：checkout → 3 个 `docker buildx build --push` → 改 tag 提交 → 打 Release，镜像推到**阿里云 ACR**。GitHub 境外 runner 跨境推送 ACR 是主要耗时点。本地到 ACR 同地域内网，且缓存用 `type=local` 复用 buildx 缓存目录，日常发版通常几分钟内完成。

## 前置条件

- macOS + **Docker Desktop**（Linux/Windows 需自行换 `sed -i` 兼容处理，脚本已按 `sed --version` 自动分流）
- `zip`（macOS 自带；Chrome 扩展只能装 zip）
- `node`（仅用于读 `local-config/token.json`；也可用 `GITHUB_TOKEN` 环境变量替代）
- ACR 账号密码（有 push 权限）
- `local-config/token.json` 里的 GitHub token（`{"github":{"username":"...","token":"ghp_..."}}`），或设 `GITHUB_TOKEN` 环境变量

## 首次配置

```bash
cd /workspace
cp .local-workflow/.env.example .local-workflow/.env
# 填 ACR_USERNAME / ACR_PASSWORD（ACR_REGISTRY 一般不用改）
```

## 命令

```bash
# 默认：镜像 → k8s tag 提交（不打 tag，避免与 tag 触发的 Action 双跑）
./.local-workflow/deploy.sh

# 版本发布：额外自动递增并 push v1.0.NN-alpha + 扩展包 + Release
./.local-workflow/deploy.sh --tag

# 先看会做什么，不动任何文件（不 build / 不 push / 不打 tag）
./.local-workflow/deploy.sh --dry-run

# 上次失败，重跑：镜像已在 ACR 就跳过重建推送
./.local-workflow/deploy.sh --skip-existing

# 只重建 widget 镜像
./.local-workflow/deploy.sh --only widget

# 免交互（CI 里用）
./.local-workflow/deploy.sh -y
```

| 参数 | 说明 |
| --- | --- |
| `--dry-run` | 只打印将执行的命令，**不修改任何文件**（k8s 也不 sed） |
| `-y, --yes` | 跳过所有交互确认 |
| `--skip-existing` | `docker manifest inspect` 命中则跳过该组件的构建推送 |
| `--only <list>` | 逗号分隔：`backend,frontend,widget` |
| `--tag` | 发布版本：自动递增并 push `v1.0.NN-alpha`、打扩展包、建 Release（默认关闭） |
| `--no-tag` | 只发镜像与 k8s 提交（默认行为；保留以显式声明） |
| `--platform <p>` | 覆盖构建平台（默认 arm64 宿主自动 `linux/amd64`） |

日志落在 `.local-workflow/output/deploy-<ts>.log`（`--dry-run` 不写日志文件）。

## 中间件镜像镜像化（mirror-middleware-images.sh）

线上 k3s 节点拉不到 Docker Hub，但 `k8s/02-postgres.yaml`（`postgres:16-alpine`）和 `k8s/03-redis.yaml`（`redis:7-alpine`）用的就是官方镜像。既然 `deploy.sh` 已经在往同一个 ACR 推镜像，把这两个也镜像过去，节点就只依赖一个 registry。

```bash
# 先看会做什么（不 pull / 不 push / 不改文件 / 不提交）
./.local-workflow/mirror-middleware-images.sh --dry-run

# 真跑：本地 tag → 推到 ACR → 改写 k8s/02、k8s/03 → commit & push main
./.local-workflow/mirror-middleware-images.sh

# 只处理其中一个
./.local-workflow/mirror-middleware-images.sh --only postgres

# 重跑：ACR 已有相同 digest 就跳过推送
./.local-workflow/mirror-middleware-images.sh --skip-existing
```

| 参数 | 说明 |
| --- | --- |
| `--dry-run` | 只打印计划，**不 pull/tag/push、不改文件、不提交** |
| `-y, --yes` | 跳过所有交互确认 |
| `--only <list>` | 逗号分隔的镜像名，如 `postgres,redis`（默认：k8s 里所有非 ACR 镜像） |
| `--skip-existing` | ACR 里该 tag 已有 digest 就跳过推送（此时也不会去 pull；**仍会校验架构**，因为镜像可能是上次推的） |
| `--skip-verify` | 跳过推送后的架构复核（省掉一次从 ACR 拉回的下载） |
| `--platform <p>` | 目标架构，默认 `linux/amd64`（k3s 节点就是 amd64） |
| `--no-pull` | 完全离线：只用本地镜像，架构不符直接报错（不自动重拉） |
| `--force-wrong-arch` | 明知架构不符也推（应急，默认禁止） |
| `--no-commit` | 只改 `k8s/` 文件，不 commit & push |

日志落在 `.local-workflow/output/mirror-<ts>.log`，每次 push 的完整输出另存 `mirror-push-<name>-<tag>.log`。

**分工**：`deploy.sh` 只管 `k8s/05|06|08`（提交清单里硬编码了这三个），中间件镜像由本脚本单独提交，两边互不覆盖。

**架构是第一号坑**：k3s 节点是 amd64，Mac arm64 上 `docker images` 里的 `postgres:16-alpine` 是 arm64，直接推上去线上 Pod 会 `exec format error`。脚本两道防线：

1. 推送前 `docker image inspect` 查本地架构，不符就 `docker pull --platform linux/amd64` 重拉；拉不到直接中止，**绝不推错架构**
2. 推送后复核 ACR 里确实是 `amd64`，不符则报错且**不改 k8s 文件**（详见下面的「推送后怎么复核架构」）

> ⚠️ Docker Desktop 用经典镜像存储（非 containerd）时，同一 tag 只能留一个架构变体，重拉可能仍是 arm64 —— 脚本会检测并报错，此时在 amd64 机器上跑或改用 `--platform`。

### 推送后怎么复核架构

`docker buildx imagetools inspect` **只有在 ref 是多架构 index 时才输出 `Platform:` 行**。普通 `docker tag` + `docker push` 推上去的是**单 manifest** 镜像，输出只有三行，压根没有平台信息：

```
Name:      crpi-.../postgres:16-alpine
MediaType: application/vnd.docker.distribution.manifest.v2+json
Digest:    sha256:...
```

所以不能靠 `imagetools inspect | grep amd64` 判断 —— 那样对单 manifest 镜像必然误报。脚本分两步：

| 情况 | 判定方式 |
| --- | --- |
| 输出含 `Platform:` 行（多架构 index） | 直接比对平台列表，不符则报错 |
| 无 `Platform:` 行（单 manifest） | **从 ACR 拉回来**再 `docker image inspect` 判架构 |
| 两步都拿不到（buildx 不可用 / 拉不回来） | 只告警并给出人工确认命令，**不阻塞** |

原则是**拿到确凿反证才中止，拿不到证据只告警**。拉回校验顺带证明了「ACR 真的拉得到」—— 这正是 k3s 节点侧需要的能力。

校验日志落在 `.local-workflow/output/mirror-verify-pull.log`；确实不想下载（postgres 约 130MB）时用 `--skip-verify`。

**ACR 推送 403**：阿里云要求镜像仓库先在控制台创建。报错时脚本会直接告诉你去 ACR 控制台 → 命名空间 `nullpointexception-i` → 新建镜像仓库 → 名称 `postgres` / `redis`（类型选「本地仓库」）。仓库已存在则是账号缺该命名空间的 push 权限。

**不加 `imagePullPolicy`**（保持最小 diff）：节点默认 `IfNotPresent`。首次切换镜像引用没问题（引用变了就是新镜像），但**以后重推同一个 tag 不会自动生效**，需要手动重启：

```bash
kubectl -n agent-sphere rollout restart deploy/postgres deploy/redis
```

**只改 `k8s/`**：`agent-sphere/agent-docker-middleware/docker-compose.yml` 也引用了 `postgres` / `redis` 官方镜像，但那是本地开发用的中间件，**保持指向 Docker Hub**，脚本不会碰它。

**幂等**：`k8s/` 里所有 image 都带 registry host 时，脚本报「无事可做」并以 0 退出 —— 可以放心重复跑。

## 流程与 deploy.yml 的对应

| deploy.yml | deploy.sh |
| --- | --- |
| checkout `ref: main`, `fetch-depth: 0` | 要求当前分支 = `main`、工作树干净，且**本地 main == origin/main**（不同步直接拒绝，避免脚本内 rebase 冲突） |
| `docker/setup-buildx-action` | `step_buildx`：内置 `docker` driver 不支持 `cache-to type=local`，自动建 `as-builder`（docker-container driver） |
| `docker/login-action` | `docker login --password-stdin`（密码不进 argv） |
| `cache-from/to: type=gha` | `type=local`，落 `.local-workflow/.cache/<组件名>` |
| `sha=${GITHUB_SHA::7}` | `sha-$(git rev-parse --short=7 HEAD)`（取法等价） |
| 3 × `build-push-action` | `step_build`，**串行**（Mac arm64 并行跑 amd64 交叉编译会抢爆内存）；`BUILDKIT_PROGRESS=plain` 保留逐层字节进度 |
| `build-args: COMMIT_HASH` | 仅 frontend 传 `--build-arg COMMIT_HASH=<sha tag>` |
| `sed` 改 `k8s/05|06|08` | `step_manifest`，同三处、同一正则；`ACR_REGISTRY` 没配时从 `k8s/05-backend.yaml` 反推 |
| `git commit` + `pull --rebase` + `push` | `step_commit`，HTTPS + `GIT_ASKPASS`（token 不进 argv / `ps` / 日志） |
| `git tag`（由 push tag 触发） | `step_tag`：脚本自己算下一个 `v1.0.NN-alpha`（patch+1、保留 `-alpha` 后缀）并 push |
| 扩展 zip（`sed` 仓库内 manifest 后再 zip） | `step_package`：复制到 `mktemp -d` 再改版本号再 zip，**不动工作树**；清单里 `inject.js`（已删除）、`content-editors.js`（不存在）自动 warn 跳过 |
| `softprops/action-gh-release` | `step_release`：`curl` 调 GitHub API（无 `gh` CLI）；已存在（422）则改为补传资产 |

顺序与 deploy.yml 一致：**tag 锚在改 k8s 之前的提交**，k8s tag 提交排在其后。

## 常见问题

**`docker buildx` 报 `Cache export is not supported`**
内置 `docker` driver 不支持本地缓存导出。脚本会自动建 `as-builder`（docker-container driver）；手动排查：

```bash
docker buildx ls            # 确认当前 builder 是 as-builder / docker-container
docker buildx use as-builder
```

**arm64 Mac 上镜像推上去但 Pod 起不来**
镜像必须是 `linux/amd64`（k3s 节点是 amd64）。脚本会自动补 `--platform linux/amd64`；已推错的镜像用 `--platform linux/arm64` 重推会覆盖同一个 tag。

**首次构建很慢、之后很快**
`.local-workflow/.cache/{backend,frontend,widget}` 是 buildx 缓存目录，别删。删了会重新拉全量依赖（backend 尤其慢）。要清就整个删。

**`pull --rebase` 冲突**
正常不会发生：preflight 已强校验本地 `main` == `origin/main`，不同步直接拒绝。若仍在 `step_commit` 撞上（远端在你发版期间被别的提交推进），脚本会**自动 `git rebase --abort`** 并退出，不会留下半成品现场；手动 `git pull --rebase origin main && git push` 后带 `--skip-existing`（镜像已推送）重跑即可。

**tag 已存在**
版本号是自动递增的，正常不会撞。撞了说明上次跑了一半：删掉本地和远端的 `v1.0.NN-alpha`（或用该 tag 建 Release），或 `--no-tag` 只发镜像。

**ACR 推送 429 / 被限流**
buildx 串行构建已减少并发；仍失败就错峰重试，或先 `--only backend` 单独发。

**中间件镜像 403 / Pod `ImagePullBackOff`**
见上面「中间件镜像镜像化」小节 —— 多半是 ACR 命名空间里还没建 `postgres` / `redis` 镜像仓库。

**token 过期**
`local-config/token.json` 换新 token，或 `GITHUB_TOKEN=ghp_xxx ./.local-workflow/deploy.sh`。token 不会写进日志。

## 本地不做测试

`.local-workflow` 只做部署，**不含 lint / test / typecheck**。改完代码仍需按各项目约定自测：

```bash
cd agent-sphere-ui && npm run biome && npm run lint && npm test
cd agent-sphere-copilot-widget && npm run typecheck && npm test && npm run build
cd agent-sphere && mvn -q -pl agent-sphere-bootstrap -am package -DskipTests
```
