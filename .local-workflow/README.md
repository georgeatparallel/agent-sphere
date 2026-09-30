# 本地 CI（.local-workflow）

在本地复刻 `.github/workflows/deploy.yml` 的全流程：构建推送三镜像 → 改 k8s 镜像 tag 并推 main → 自动递增版本号、打包 Chrome 扩展、建 GitHub Release。**GitHub Action 慢时用它替代**；`.env` / `.cache/` / `output/` 已 gitignore。

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
# 完整发版：镜像 → k8s tag 提交 → v1.0.NN-alpha → 扩展包 → Release
./.local-workflow/deploy.sh

# 先看会做什么，不动任何文件（不 build / 不 push / 不打 tag）
./.local-workflow/deploy.sh --dry-run

# 上次失败，重跑：镜像已在 ACR 就跳过重建推送
./.local-workflow/deploy.sh --skip-existing

# 只发后端镜像 + k8s 提交，不发 Release
./.local-workflow/deploy.sh --no-tag

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
| `--no-tag` | 只发镜像与 k8s 提交，不打 tag、不打包扩展、不建 Release |
| `--platform <p>` | 覆盖构建平台（默认 arm64 宿主自动 `linux/amd64`） |

日志落在 `.local-workflow/output/deploy-<ts>.log`（`--dry-run` 不写日志文件）。

## 流程与 deploy.yml 的对应

| deploy.yml | deploy.sh |
| --- | --- |
| checkout `ref: main`, `fetch-depth: 0` | 要求当前分支 = `main` 且工作树干净（更严：脏树直接拒绝） |
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
脚本会停下来并提示，此时手动 `git rebase --abort`，处理完 `git pull --rebase origin main && git push`，再带 `--only <未成功的组件>` 重跑。

**tag 已存在**
版本号是自动递增的，正常不会撞。撞了说明上次跑了一半：删掉本地和远端的 `v1.0.NN-alpha`（或用该 tag 建 Release），或 `--no-tag` 只发镜像。

**ACR 推送 429 / 被限流**
buildx 串行构建已减少并发；仍失败就错峰重试，或先 `--only backend` 单独发。

**token 过期**
`local-config/token.json` 换新 token，或 `GITHUB_TOKEN=ghp_xxx ./.local-workflow/deploy.sh`。token 不会写进日志。

## 本地不做测试

`.local-workflow` 只做部署，**不含 lint / test / typecheck**。改完代码仍需按各项目约定自测：

```bash
cd agent-sphere-ui && npm run biome && npm run lint && npm test
cd agent-sphere-copilot-widget && npm run typecheck && npm test && npm run build
cd agent-sphere && mvn -q -pl agent-sphere-bootstrap -am package -DskipTests
```
