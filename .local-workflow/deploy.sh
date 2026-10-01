#!/usr/bin/env bash
#
# 本地 CI —— 复刻 .github/workflows/deploy.yml 全流程：
#   ACR 登录 → buildx 构建并推送 backend/frontend/widget 三镜像
#            → 改写 k8s/05|06|08 镜像 tag 并 commit&push main（触发 Fleet）
#            → 自动递增 v1.0.NN-alpha、打包 Chrome 扩展、建 GitHub Release
#
# 与 deploy.yml 的差异（刻意为之）：
#   1. 缓存用 type=local 落到 .local-workflow/.cache/，替代 CI 的 type=gha
#      —— 本地到阿里云 ACR 是同地域内网，比 GitHub 境外 runner 跨境推送快得多
#   2. 三个镜像串行构建：Mac(arm64) 上并行跑 amd64 交叉编译会抢爆内存
#   3. 自动递增版本号并直接建 Release，不用来回看 Actions 日志
#   4. 扩展打包在临时目录改版本号，不污染工作树（deploy.yml 改了不还原）
#   5. git 一律走 HTTPS + GIT_ASKPASS，token 不进 argv、不进日志
#
# 兼容 macOS 自带 bash 3.2：不用关联数组 / mapfile / ${v,,} / 空数组展开。
#
# 用法：./.local-workflow/deploy.sh [选项]     完整说明见同目录 README.md

set -euo pipefail

# ---------------------------------------------------------------- 路径与常量

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

WF_DIR="${SCRIPT_DIR}"
ENV_FILE="${WF_DIR}/.env"
CACHE_DIR="${WF_DIR}/.cache"
OUTPUT_DIR="${WF_DIR}/output"

# 三个镜像的固定映射。顺序即 deploy.yml 的构建顺序：backend → frontend → widget
COMPONENTS="backend frontend widget"
component_name() {
  case "$1" in
    backend)  echo "agent-sphere-backend" ;;
    frontend) echo "agent-sphere-frontend" ;;
    widget)   echo "agent-sphere-widget" ;;
    *) return 1 ;;
  esac
}
component_context() {
  case "$1" in
    backend)  echo "agent-sphere" ;;
    frontend) echo "agent-sphere-ui" ;;
    widget)   echo "agent-sphere-copilot-widget" ;;
    *) return 1 ;;
  esac
}
component_manifest() {
  case "$1" in
    backend)  echo "k8s/05-backend.yaml" ;;
    frontend) echo "k8s/06-frontend.yaml" ;;
    widget)   echo "k8s/08-widget.yaml" ;;
    *) return 1 ;;
  esac
}

# 扩展 zip 文件清单，沿用 deploy.yml。清单里已失效的条目只 warn 跳过：
#   inject.js            —— 按 agent-sphere-chrome-extension/AGENTS.md 已删除
#   content-editors.js   —— 仓库中不存在
EXTENSION_FILES="manifest.json background.js lib content.js content-locator.js content-editors.js page-script.js inject.js popup.html popup.js offscreen.html offscreen.js icon-16.png icon-32.png icon-48.png icon-128.png"

# macOS(BSD) 与 GNU 的 sed -i 语法不同：BSD 必须写成 -i ''，GNU 是 -i
if sed --version >/dev/null 2>&1; then
  SED_I=(-i)
else
  SED_I=(-i '')
fi

# ---------------------------------------------------------------- 运行时状态

DRY_RUN=0
ASSUME_YES=0
SKIP_EXISTING=0
NO_TAG=1                # 默认不打 tag（避免与 tag 触发的 GitHub Action 双跑）；--tag 开启
ONLY=""                # 空 = 三个全建；否则 " backend widget " 形式
PLATFORM=""            # 空 = 自动探测（arm64 宿主补 linux/amd64）

# 凭据优先级：进程环境变量 > .local-workflow/.env > 默认值/反推。
# 所以先把环境里已有的值抓成快照（下面的空赋值只为声明默认），load_env 之后再挑回来。
ENV_ACR_REGISTRY="${ACR_REGISTRY:-}"
ENV_ACR_USERNAME="${ACR_USERNAME:-}"
ENV_ACR_PASSWORD="${ACR_PASSWORD:-}"
ENV_GITHUB_REPO="${GITHUB_REPO:-}"
ENV_GITHUB_TOKEN="${GITHUB_TOKEN:-}"

ACR_REGISTRY=""
ACR_USERNAME=""
ACR_PASSWORD=""
GITHUB_REPO=""
GITHUB_TOKEN=""

SRC_SHA=""             # 流程开始时的 HEAD：镜像 tag 与扩展包都锚在这里
IMAGE_TAG=""           # sha-XXXXXXX
LATEST_TAG=""
NEXT_TAG=""
ZIP_PATH=""
LOG_FILE=""
ASKPASS_SCRIPT=""
TMP_DIR=""

# ---------------------------------------------------------------- 日志

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
  C_BLU=$'\033[34m'; C_DIM=$'\033[2m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_DIM=""; C_RST=""
fi

log()  { printf '%s==>%s %s\n' "${C_BLU}" "${C_RST}" "$*"; }
ok()   { printf '%s  ok%s %s\n'  "${C_GRN}" "${C_RST}" "$*"; }
warn() { printf '%s   !%s %s\n' "${C_YEL}" "${C_RST}" "$*" >&2; }
err()  { printf '%s   x%s %s\n' "${C_RED}" "${C_RST}" "$*" >&2; }
die()  { err "$*"; exit 1; }
dim()  { printf '%s     %s%s\n' "${C_DIM}" "$*" "${C_RST}"; }
hr()   { printf '%s%s%s\n' "${C_DIM}" "--------------------------------------------------------------" "${C_RST}"; }

on_err() {
  local code=$?
  err "失败（exit ${code}）。日志：${LOG_FILE:-未启用}"
  exit "${code}"
}

cleanup() {
  if [ -n "${ASKPASS_SCRIPT}" ] && [ -f "${ASKPASS_SCRIPT}" ]; then
    rm -f "${ASKPASS_SCRIPT}" "${ASKPASS_SCRIPT}.token"
  fi
  if [ -n "${TMP_DIR}" ] && [ -d "${TMP_DIR}" ]; then
    rm -rf "${TMP_DIR}"
  fi
  return 0
}
trap cleanup EXIT

# ---------------------------------------------------------------- 配置

load_env() {
  [ -f "${ENV_FILE}" ] || { warn "未找到 ${ENV_FILE}（可复制 .env.example；registry 会从 k8s manifest 反推）"; return 0; }
  # 经临时文件再 source：顺手吃掉 CRLF，免得像值后面挂个 \r
  local sanitized
  sanitized="$(mktemp)"
  tr -d '\r' < "${ENV_FILE}" > "${sanitized}"
  set -a
  # shellcheck disable=SC1090
  . "${sanitized}"
  set +a
  rm -f "${sanitized}"
  ok "已加载 ${ENV_FILE}"
}

# 把 load_env 读到的 .env 值与进程环境变量快照合并，环境变量优先。
# 必须在 load_env 之后、任何使用凭据的步骤之前调用。
resolve_env() {
  [ -n "${ENV_ACR_REGISTRY}" ] && ACR_REGISTRY="${ENV_ACR_REGISTRY}"
  [ -n "${ENV_ACR_USERNAME}" ] && ACR_USERNAME="${ENV_ACR_USERNAME}"
  [ -n "${ENV_ACR_PASSWORD}" ] && ACR_PASSWORD="${ENV_ACR_PASSWORD}"
  [ -n "${ENV_GITHUB_REPO}" ] && GITHUB_REPO="${ENV_GITHUB_REPO}"
  [ -n "${ENV_GITHUB_TOKEN}" ] && GITHUB_TOKEN="${ENV_GITHUB_TOKEN}"
  return 0
}

# registry 前缀优先取 .env；没填就从 k8s/05-backend.yaml 现有 image 行反推，
# 这样「只配凭据不配 registry」也能跑，且不必把 registry 写死在脚本里
resolve_registry() {
  if [ -z "${ACR_REGISTRY}" ]; then
    local line
    line="$(grep -m1 -E '^[[:space:]]*image:[[:space:]]*\S+/agent-sphere-backend:' k8s/05-backend.yaml || true)"
    [ -n "${line}" ] || die "无法从 k8s/05-backend.yaml 反推 ACR registry，请在 .env 里填 ACR_REGISTRY"
    ACR_REGISTRY="${line#*image:}"
    ACR_REGISTRY="${ACR_REGISTRY# }"
    ACR_REGISTRY="${ACR_REGISTRY%/agent-sphere-backend:*}"
    warn "ACR_REGISTRY 未配置，已从 k8s/05-backend.yaml 反推：${ACR_REGISTRY}"
  fi
  ACR_REGISTRY="${ACR_REGISTRY%/}"
}

resolve_github_token() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    ok "GITHUB_TOKEN 来自环境变量"
    return 0
  fi
  local cfg="${REPO_ROOT}/local-config/token.json"
  [ -f "${cfg}" ] || return 1
  GITHUB_TOKEN="$(node -p "require('${cfg}').github.token" 2>/dev/null || true)"
  [ -n "${GITHUB_TOKEN}" ] || die "无法从 ${cfg} 解析 github.token"
  ok "GITHUB_TOKEN 来自 local-config/token.json"
}

# GIT_ASKPASS：token 只落在临时文件里，不进 argv / ps 输出 / 日志。
# 不用 https://user:token@host 内嵌 URL —— 那种写法 token 会进 shell history 与进程列表。
setup_askpass() {
  [ -n "${ASKPASS_SCRIPT}" ] && return 0
  ASKPASS_SCRIPT="$(mktemp)"
  chmod 700 "${ASKPASS_SCRIPT}"
  printf '%s' "${GITHUB_TOKEN}" > "${ASKPASS_SCRIPT}.token"
  chmod 600 "${ASKPASS_SCRIPT}.token"
  {
    echo '#!/bin/sh'
    echo 'case "$1" in'
    echo '  *sername*) echo x-access-token ;;'
    echo '  *) cat "$0.token" ;;'
    echo 'esac'
  } > "${ASKPASS_SCRIPT}"
}

repo_url() {
  printf 'https://github.com/%s' "${GITHUB_REPO}"
}

git_push_url() {
  # $1 = <refspec>，如 HEAD:main 或 v1.0.111-alpha
  setup_askpass
  GIT_ASKPASS="${ASKPASS_SCRIPT}" GIT_TERMINAL_PROMPT=0 \
    git push "$(repo_url)" "$1" 2>&1 | sed 's/^/     /'
}

# ---------------------------------------------------------------- 参数

usage() {
  cat <<'EOF'
用法：./.local-workflow/deploy.sh [选项]

  --dry-run              只打印将要执行的命令（不 build / 不 push / 不建 Release）
  -y, --yes              跳过所有交互确认
  --skip-existing        目标 sha 镜像在 ACR 已存在则跳过构建推送（重跑用）
  --only <list>          只处理指定组件，逗号分隔：backend,frontend,widget
  --tag                  发布版本：自动递增并 push v1.0.NN-alpha，打扩展包并建 Release（默认关闭）
  --no-tag               只发镜像与 k8s 提交（默认行为；保留以显式声明）
  --platform <p>         覆盖构建平台（默认：arm64 宿主自动用 linux/amd64）
  -h, --help             显示本帮助
EOF
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run)       DRY_RUN=1 ;;
      -y|--yes)        ASSUME_YES=1 ;;
      --skip-existing) SKIP_EXISTING=1 ;;
      --tag)           NO_TAG=0 ;;
      --no-tag)        NO_TAG=1 ;;   # 默认行为，保留以显式声明
      --only)          [ $# -ge 2 ] || die "--only 需要参数"; ONLY="$2"; shift ;;
      --only=*)        ONLY="${1#*=}" ;;
      --platform)      [ $# -ge 2 ] || die "--platform 需要参数"; PLATFORM="$2"; shift ;;
      --platform=*)    PLATFORM="${1#*=}" ;;
      -h|--help)       usage; exit 0 ;;
      *)               die "未知参数：$1（-h 看用法）" ;;
    esac
    shift
  done

  if [ -n "${ONLY}" ]; then
    local c
    for c in ${ONLY//,/ }; do
      case " ${COMPONENTS} " in
        *" ${c} "*) : ;;
        *) die "--only 含未知组件：${c}（可选 backend,frontend,widget）" ;;
      esac
    done
    ONLY=" ${ONLY//,/ } "
  fi

}

selected_components() {
  if [ -n "${ONLY}" ]; then
    printf '%s' "${ONLY}"
  else
    printf ' %s ' "${COMPONENTS}"
  fi
}

confirm() {
  local prompt="$1"
  if [ "${ASSUME_YES}" -eq 1 ] || [ "${DRY_RUN}" -eq 1 ]; then
    return 0
  fi
  local ans=""
  printf '%s   ? %s [y/N] ' "${C_YEL}" "${prompt}"
  read -r ans || true
  case "${ans}" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

image_ref() {
  printf '%s/%s:%s' "${ACR_REGISTRY}" "$(component_name "$1")" "${IMAGE_TAG}"
}

# ---------------------------------------------------------------- 步骤

step_preflight() {
  log "preflight"
  local tool missing=0
  for tool in git curl sed zip node; do
    if command -v "${tool}" >/dev/null 2>&1; then
      dim "${tool} ok"
    elif [ "${tool}" = "zip" ] && [ "${DRY_RUN}" -eq 1 ]; then
      warn "缺少 zip —— dry-run 继续（真实执行会失败）"
    elif [ "${tool}" = "node" ]; then
      warn "缺少 node（仅用于从 local-config/token.json 读 token，也可用 GITHUB_TOKEN 环境变量替代）"
    else
      if [ "${tool}" = "zip" ]; then
        err "缺少 zip（macOS 自带；打包 Chrome 扩展 zip 必需）"
      else
        err "缺少 ${tool}"
      fi
      missing=1
    fi
  done
  [ "${missing}" -eq 0 ] || exit 1

  if command -v docker >/dev/null 2>&1; then
    ok "docker $(docker version --format '{{.Client.Version}}' 2>/dev/null || echo '?')"
  elif [ "${DRY_RUN}" -eq 1 ]; then
    warn "未安装 docker —— dry-run 继续（真实执行会失败）"
  else
    die "未安装 docker。macOS 请装 Docker Desktop：https://docs.docker.com/desktop/install/mac-install/"
  fi

  if [ -z "${ACR_USERNAME}" ] || [ -z "${ACR_PASSWORD}" ]; then
    if [ "${DRY_RUN}" -eq 1 ]; then
      warn "未配置 ACR_USERNAME / ACR_PASSWORD —— dry-run 继续"
    else
      die "未配置 ACR_USERNAME / ACR_PASSWORD。复制 .env.example 为 .env 并填写"
    fi
  else
    ok "ACR 凭据已配置（${ACR_USERNAME}）"
  fi
  ok "registry ${ACR_REGISTRY}"

  local branch
  branch="$(git rev-parse --abbrev-ref HEAD)"
  [ "${branch}" = "main" ] || die "当前分支是 ${branch}，请先切到 main（镜像与 tag 都锚在 main 上）"

  if [ -n "$(git status --porcelain)" ]; then
    git status --short | sed 's/^/     /' >&2
    die "工作树不干净。请先 commit 或 stash —— 本脚本会直接 push main，不能带着脏树发版"
  fi
  ok "分支 main，工作树干净"

  SRC_SHA="$(git rev-parse HEAD)"
  IMAGE_TAG="sha-${SRC_SHA:0:7}"
  ok "源码提交 ${SRC_SHA} → 镜像 tag ${IMAGE_TAG}"

  [ -n "${GITHUB_REPO}" ] || GITHUB_REPO="nullpointexception-i/agent-sphere"

  # 发版前强校验本地 main 与 origin/main 同步：否则脚本内的 pull --rebase 会重放
  # 本地分叉提交并可能冲突，把工作副本留在半成品 rebase 现场（踩过：.gitignore 冲突）。
  if resolve_github_token; then
    setup_askpass
    if GIT_ASKPASS="${ASKPASS_SCRIPT}" GIT_TERMINAL_PROMPT=0 \
       git fetch "$(repo_url)" main 2>&1 | sed 's/^/     /'; then
      local remote_head
      remote_head="$(git rev-parse FETCH_HEAD)"
      if [ "$(git rev-parse HEAD)" = "${remote_head}" ]; then
        ok "本地 main 与 origin/main 一致（$(git rev-parse --short HEAD)）"
      elif [ "${DRY_RUN}" -eq 1 ]; then
        warn "本地 main 与 origin/main 不一致（dry-run 继续）：本地 $(git rev-parse --short HEAD) / 远端 $(git rev-parse --short FETCH_HEAD)"
      else
        die "本地 main 与 origin/main 不一致（本地 $(git rev-parse --short HEAD) / 远端 $(git rev-parse --short FETCH_HEAD)）。请先 git pull --rebase 同步 main 后再发版，避免脚本内 rebase 冲突"
      fi
    elif [ "${DRY_RUN}" -eq 1 ]; then
      warn "fetch origin/main 失败（dry-run 跳过同步校验）"
    else
      die "fetch origin/main 失败，请检查网络 / GITHUB_TOKEN"
    fi
  elif [ "${DRY_RUN}" -eq 1 ]; then
    warn "未找到 GitHub token：dry-run 跳过同步校验（真实执行 push main 必需）"
  else
    die "缺少 GitHub token：设置 GITHUB_TOKEN 环境变量，或准备 local-config/token.json"
  fi
  return 0
}

step_buildx() {
  # 平台探测只依赖 uname，与 docker 在不在无关 —— dry-run 也要把 --platform 打出来
  if [ -z "${PLATFORM}" ]; then
    case "$(uname -m)" in
      arm64|aarch64|x86_64) PLATFORM="linux/amd64" ;;
      *) PLATFORM="linux/$(uname -m)" ;;
    esac
  fi
  dim "构建平台 ${PLATFORM}（k3s 节点是 amd64，arm64 宿主必须交叉编译）"

  command -v docker >/dev/null 2>&1 || return 0
  docker buildx version >/dev/null 2>&1 || die "docker buildx 不可用"

  local driver
  driver="$(docker buildx inspect 2>/dev/null | grep -m1 '^Driver:' | awk '{print $2}' || true)"
  if [ "${driver}" != "docker-container" ]; then
    if docker buildx inspect as-builder >/dev/null 2>&1; then
      docker buildx use as-builder >/dev/null
    else
      log "创建 docker-container builder（内置 ${driver:-docker} driver 不支持导出本地缓存）"
      docker buildx create --name as-builder --driver docker-container --use >/dev/null
      docker buildx inspect --bootstrap >/dev/null
    fi
    ok "builder as-builder 已就绪（docker-container，支持 type=local 缓存）"
  else
    ok "当前 builder 已是 docker-container driver"
  fi
  return 0
}

step_login() {
  log "登录 ACR ${ACR_REGISTRY}"
  if [ "${DRY_RUN}" -eq 1 ]; then
    dim "docker login ${ACR_REGISTRY} -u <ACR_USERNAME> --password-stdin（跳过）"
    return 0
  fi
  printf '%s' "${ACR_PASSWORD}" | docker login "${ACR_REGISTRY}" \
    --username "${ACR_USERNAME}" --password-stdin >/dev/null
  ok "已登录 ${ACR_REGISTRY}"
  return 0
}

step_build() {
  local comp image ctx
  for comp in $(selected_components); do
    image="$(image_ref "${comp}")"
    ctx="$(component_context "${comp}")"
    [ -d "${ctx}" ] || die "构建上下文不存在：${ctx}"

    hr
    log "构建并推送 ${comp} → ${image}"

    if [ "${SKIP_EXISTING}" -eq 1 ] && [ "${DRY_RUN}" -eq 0 ]; then
      if docker manifest inspect "${image}" >/dev/null 2>&1; then
        ok "ACR 已有 ${IMAGE_TAG}，--skip-existing 跳过 ${comp}"
        continue
      fi
    fi

    set -- buildx build --platform "${PLATFORM}" --push \
      --provenance=false --sbom=false \
      --cache-from "type=local,src=${CACHE_DIR}/${comp}" \
      --cache-to "type=local,dest=${CACHE_DIR}/${comp},mode=max"
    if [ "${comp}" = "frontend" ]; then
      set -- "$@" --build-arg "COMMIT_HASH=${IMAGE_TAG}"
    fi
    set -- "$@" --tag "${image}" "${ctx}"

    # plain 进度：buildx 默认折叠输出，推层时长时间只有一行动不了
    if [ "${DRY_RUN}" -eq 1 ]; then
      dim "BUILDKIT_PROGRESS=plain docker $*"
      continue
    fi
    BUILDKIT_PROGRESS=plain docker "$@"
    ok "${comp} 推送完成"
  done
  return 0
}

step_manifest() {
  log "改写 k8s 镜像 tag → ${IMAGE_TAG}"
  local comp manifest name
  for comp in $(selected_components); do
    manifest="$(component_manifest "${comp}")"
    name="$(component_name "${comp}")"
    [ -f "${manifest}" ] || die "manifest 不存在：${manifest}"
    if [ "${DRY_RUN}" -eq 1 ]; then
      # dry-run 绝不碰工作树：只把「改完会变成什么样」打出来
      dim "${manifest}: image: ${ACR_REGISTRY}/${name}:${IMAGE_TAG}"
      continue
    fi
    sed "${SED_I[@]}" "s|image: .*/${name}:.*|image: ${ACR_REGISTRY}/${name}:${IMAGE_TAG}|" "${manifest}"
    dim "${manifest}"
  done
  if [ "${DRY_RUN}" -eq 0 ]; then
    ok "k8s tag 已更新"
  fi
  return 0
}

step_commit() {
  if [ "${DRY_RUN}" -eq 1 ]; then
    # dry-run 没真的 sed 过，用「当前值 vs 目标值」判断有没有东西要提交
    local c manifest want cur pending=0
    for c in $(selected_components); do
      manifest="$(component_manifest "${c}")"
      want="${ACR_REGISTRY}/$(component_name "${c}"):${IMAGE_TAG}"
      cur="$(grep -m1 -E 'image: .*/agent-sphere-' "${manifest}" | awk '{print $2}' || true)"
      if [ "${cur}" = "${want}" ]; then
        dim "${manifest}: 已是 ${IMAGE_TAG}，无需变更"
      else
        dim "${manifest}: ${cur} → ${want}"
        pending=1
      fi
    done
    if [ "${pending}" -eq 1 ]; then
      dim "（dry-run）将执行：git commit -m 'chore: update image tags [skip ci]' → pull --rebase → push origin/main"
    else
      warn "k8s 镜像 tag 无变化，将跳过提交"
    fi
    return 0
  fi

  local changed
  changed="$(git status --porcelain -- k8s/05-backend.yaml k8s/06-frontend.yaml k8s/08-widget.yaml)"
  if [ -z "${changed}" ]; then
    warn "k8s 镜像 tag 无变化，跳过提交"
    return 0
  fi

  log "提交并推送 k8s tag（Fleet 靠这个 commit 自动 apply）"
  confirm "确认 push 到 origin/main？" || die "已取消"
  git add k8s/05-backend.yaml k8s/06-frontend.yaml k8s/08-widget.yaml
  git commit -m "chore: update image tags [skip ci]" >/dev/null

  setup_askpass
  GIT_ASKPASS="${ASKPASS_SCRIPT}" GIT_TERMINAL_PROMPT=0 \
    git fetch "$(repo_url)" main 2>&1 | sed 's/^/     /' || warn "fetch 失败，继续尝试 push"
  if ! GIT_ASKPASS="${ASKPASS_SCRIPT}" GIT_TERMINAL_PROMPT=0 \
       git pull --rebase "$(repo_url)" main 2>&1 | sed 's/^/     /'; then
    # 不把工作副本留在半成品 rebase 现场：abort 后交还人工处理
    git rebase --abort >/dev/null 2>&1 || true
    die "pull --rebase 冲突，已自动 abort。请手动 git pull --rebase origin main 解决后重跑（镜像已推送，可加 --skip-existing）"
  fi
  git_push_url HEAD:main
  ok "已推送 main（$(git rev-parse --short HEAD)）"
  return 0
}

# v1.0.110-alpha → v1.0.111-alpha（patch+1，保留 -alpha/-beta 后缀）
bump_version() {
  local latest="$1"
  if [[ "${latest}" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-.+)?$ ]]; then
    local next="v${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$(( ${BASH_REMATCH[3]} + 1 ))"
    if [ -n "${BASH_REMATCH[4]}" ]; then
      next="${next}${BASH_REMATCH[4]}"
    fi
    printf '%s' "${next}"
  else
    die "无法解析 tag 格式：${latest}（期望 v<major>.<minor>.<patch>[-suffix]）"
  fi
}

step_tag() {
  if [ "${NO_TAG}" -eq 1 ]; then
    warn "未启用 tag（默认 no-tag；加 --tag 开启）：跳过打 tag / 扩展打包 / Release"
    return 0
  fi

  log "计算下一个版本号"
  LATEST_TAG="$(git tag --list 'v*' --sort=-v:refname | head -1)"
  [ -n "${LATEST_TAG}" ] || die "仓库里没有任何 v* tag，无法推断下一个版本号"
  NEXT_TAG="$(bump_version "${LATEST_TAG}")"
  dim "${LATEST_TAG} → ${NEXT_TAG}"

  if git rev-parse -q --verify "refs/tags/${NEXT_TAG}" >/dev/null 2>&1; then
    die "tag ${NEXT_TAG} 已存在（上次只跑了一半？）——处理掉后重跑，或改用 --no-tag"
  fi

  if [ "${DRY_RUN}" -eq 1 ]; then
    dim "（dry-run）将执行：git tag -a ${NEXT_TAG} -m ${NEXT_TAG} ${SRC_SHA} && git push ${NEXT_TAG}"
    return 0
  fi

  confirm "确认在 ${SRC_SHA:0:7} 上打 tag ${NEXT_TAG} 并推送？" || die "已取消"
  git tag -a "${NEXT_TAG}" -m "${NEXT_TAG}" "${SRC_SHA}"
  git_push_url "${NEXT_TAG}"
  ok "已推送 tag ${NEXT_TAG}"
  return 0
}

step_package() {
  if [ "${NO_TAG}" -eq 1 ]; then
    return 0
  fi
  local ext_dir="agent-sphere-chrome-extension"
  [ -d "${ext_dir}" ] || die "扩展目录不存在：${ext_dir}"

  # Chrome 只接受纯点分数字版本：v1.0.111-alpha → 1.0.111
  local version
  version="$(printf '%s' "${NEXT_TAG}" | sed -e 's/^v//' -e 's/-.*$//')"
  ZIP_PATH="${OUTPUT_DIR}/agent-sphere-chrome-extension-${NEXT_TAG}.zip"

  log "打包 Chrome 扩展（version ${version}）"
  mkdir -p "${OUTPUT_DIR}"
  TMP_DIR="$(mktemp -d)"

  local f staged="" missing=""
  for f in ${EXTENSION_FILES}; do
    if [ -e "${ext_dir}/${f}" ]; then
      cp -R "${ext_dir}/${f}" "${TMP_DIR}/"
      staged="${staged} ${f}"
    else
      missing="${missing} ${f}"
    fi
  done
  staged="${staged# }"
  if [ -n "${missing# }" ]; then
    warn "清单中已不存在的文件已跳过：${missing# }"
  fi
  [ -n "${staged}" ] || die "扩展文件清单全部缺失，检查 ${ext_dir}"

  # 在临时目录改版本号。deploy.yml 直接改仓库里的 manifest.json / package.json 且不还原，
  # 会把工作树弄脏（下次 preflight 直接拦住），这里从根上避免。
  sed "${SED_I[@]}" "s|\"version\": *\"[^\"]*\"|\"version\": \"${version}\"|" "${TMP_DIR}/manifest.json"
  if [ -f "${TMP_DIR}/package.json" ]; then
    sed "${SED_I[@]}" "s|\"version\": *\"[^\"]*\"|\"version\": \"${version}\"|" "${TMP_DIR}/package.json"
  fi

  if [ "${DRY_RUN}" -eq 1 ]; then
    dim "（dry-run）将执行：cd <tmp> && zip -r ${ZIP_PATH} ${staged}"
    rm -rf "${TMP_DIR}"
    TMP_DIR=""
    return 0
  fi

  ( cd "${TMP_DIR}" && zip -q -r "${ZIP_PATH}" ${staged} )
  ok "扩展包 ${ZIP_PATH}（$(du -h "${ZIP_PATH}" | cut -f1)）"
  return 0
}

step_release() {
  if [ "${NO_TAG}" -eq 1 ]; then
    return 0
  fi
  local api="https://api.github.com/repos/${GITHUB_REPO}/releases"
  log "创建 GitHub Release ${NEXT_TAG}"

  if [ "${DRY_RUN}" -eq 1 ]; then
    dim "（dry-run）将执行：POST ${api} {tag_name:${NEXT_TAG}, generate_release_notes:true}，再上传 agent-sphere-chrome-extension-${NEXT_TAG}.zip"
    return 0
  fi

  [ -n "${ZIP_PATH}" ] && [ -f "${ZIP_PATH}" ] || die "扩展包缺失：${ZIP_PATH:-<未打包>}"

  local payload body code release_id
  payload="$(printf '{"tag_name":"%s","name":"%s","generate_release_notes":true}' "${NEXT_TAG}" "${NEXT_TAG}")"
  body="$(mktemp)"
  code="$(curl -sS -X POST "${api}" \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -d "${payload}" -o "${body}" -w '%{http_code}')"

  if [ "${code}" = "422" ]; then
    warn "Release ${NEXT_TAG} 已存在（422），改为向已有 Release 补传资产"
    release_id="$(curl -sS "${api}/tags/${NEXT_TAG}" \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json" \
      | node -p 'JSON.parse(require("fs").readFileSync(0,"utf8")).id')"
  elif [ "${code}" = "201" ]; then
    release_id="$(node -p 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).id' "${body}")"
  else
    err "创建 Release 失败（HTTP ${code}）：$(head -c 400 "${body}")"
    exit 1
  fi
  rm -f "${body}"

  local asset_name asset_code
  asset_name="$(basename "${ZIP_PATH}")"
  asset_code="$(curl -sS -X POST \
    "https://uploads.github.com/repos/${GITHUB_REPO}/releases/${release_id}/assets?name=${asset_name}" \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/zip" \
    --data-binary "@${ZIP_PATH}" -o /dev/null -w '%{http_code}')"
  [ "${asset_code}" = "201" ] || die "上传资产失败（HTTP ${asset_code}）：${asset_name}"

  ok "Release 就绪：$(curl -sS "${api}/tags/${NEXT_TAG}" \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json" \
      | node -p 'JSON.parse(require("fs").readFileSync(0,"utf8")).html_url')"
  return 0
}

summary() {
  hr
  log "汇总"
  local comp
  for comp in $(selected_components); do
    dim "$(component_name "${comp}") → $(image_ref "${comp}")"
  done
  dim "k8s → 05-backend.yaml / 06-frontend.yaml / 08-widget.yaml"
  if [ "${NO_TAG}" -eq 0 ]; then
    dim "tag ${NEXT_TAG:-（未创建）}    扩展包 ${OUTPUT_DIR}/agent-sphere-chrome-extension-${NEXT_TAG:-<tag>}.zip"
  fi
  dim "源码提交 ${SRC_SHA:0:7}（tag 锚在此提交，k8s 提交排在其后，与 deploy.yml 同序）"
  if [ "${DRY_RUN}" -eq 0 ] && [ "${NO_TAG}" -eq 0 ]; then
    echo
    printf '  验证：%skubectl -n agent-sphere get pods%s   /   https://as.buukle.top\n' "${C_BLU}" "${C_RST}"
  fi
  echo
  return 0
}

# ---------------------------------------------------------------- main

main() {
  trap on_err ERR
  parse_args "$@"
  load_env
  resolve_env
  resolve_registry
  mkdir -p "${OUTPUT_DIR}" "${CACHE_DIR}"

  if [ "${DRY_RUN}" -eq 1 ]; then
    warn "dry-run：不 build、不 push、不打 tag、不建 Release，也不会改动任何文件"
  else
    LOG_FILE="${OUTPUT_DIR}/deploy-$(date +%Y%m%d-%H%M%S).log"
    exec > >(tee -a "${LOG_FILE}") 2>&1
    log "日志 ${LOG_FILE}"
  fi

  step_preflight
  step_buildx
  step_login
  step_build
  step_manifest
  step_commit
  step_tag
  step_package
  step_release
  summary
}

main "$@"
