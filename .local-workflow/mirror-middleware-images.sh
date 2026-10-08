#!/usr/bin/env bash
#
# 把 k8s 里拉不到的第三方镜像（postgres / redis）从本地推到阿里云 ACR，并改写 k8s manifest 指向 ACR。
#
# 背景：线上 k3s 节点拉不到 Docker Hub（postgres:16-alpine / redis:7-alpine），
# 但本机能拉到。既然 CI 脚本 .local-workflow/deploy.sh 已经在往同一个 ACR 推镜像，
# 那就把这两个中间件镜像也镜像过去，让节点只依赖一个 registry。
#
# 与 deploy.sh 的分工：
#   deploy.sh                   负责 agent-sphere-backend / frontend / widget（05/06/08）
#   mirror-middleware-images.sh 负责 postgres / redis（02/03）—— deploy.sh 的提交清单里
#                               硬编码了 05/06/08，不会带上这两个文件，所以必须自己提交。
#
# 两个最容易翻车的点，脚本里都做了硬约束：
#   1. 架构：k3s 节点是 amd64（见根 AGENTS.md）。本地若是 Apple Silicon，
#      `docker images` 里的 postgres:16-alpine 是 arm64，推上去线上直接 exec format error。
#      所以推送前强制校验架构，不符则按目标平台重拉；推送后再用 imagetools 复核 ACR 里确实是 amd64。
#   2. ACR 命名空间里可能压根没有 postgres/redis 这两个镜像仓库（阿里云要求先在控制台建），
#      push 会返回 403。脚本把它翻译成一句能直接照做的提示。
#
# 兼容 macOS 自带 bash 3.2：不用关联数组 / mapfile / ${v,,}。
#
# 用法：./.local-workflow/mirror-middleware-images.sh [选项]   详见同目录 README.md

set -euo pipefail

# ---------------------------------------------------------------- 路径与常量

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

WF_DIR="${SCRIPT_DIR}"
ENV_FILE="${WF_DIR}/.env"
OUTPUT_DIR="${WF_DIR}/output"

# k3s 节点架构。根 AGENTS.md 与 deploy.sh 都写明节点是 amd64；
# arm64 宿主上构建/推送时必须交叉到它，否则 Pod 起不来。
DEFAULT_PLATFORM="linux/amd64"

# 只处理 k8s/ 下的 manifest。agent-docker-middleware/docker-compose.yml 也引用了
# postgres/redis 官方镜像，但那是本地开发用的中间件，**不能**跟着改 —— 它本来就该走 Docker Hub。
K8S_DIR="k8s"

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
NO_COMMIT=0
NO_PULL=0
FORCE_WRONG_ARCH=0
ONLY=""
PLATFORM="${DEFAULT_PLATFORM}"

# 先把「进入脚本时环境里已有的值」存进 ENV_*，load_env 之后再决定谁优先。
ENV_ACR_REGISTRY="${ACR_REGISTRY:-}"
ENV_ACR_USERNAME="${ACR_USERNAME:-}"
ENV_ACR_PASSWORD="${ACR_PASSWORD:-}"
ENV_GITHUB_REPO="${GITHUB_REPO:-}"
ENV_GITHUB_TOKEN="${GITHUB_TOKEN:-}"

# 这几个用 :- 兜底而不是直接 ="" ：直接赋值会把「外部 export 的 GITHUB_TOKEN」抹掉，
# 导致 GITHUB_TOKEN=ghp_xxx ./deploy.sh 这种用法失效（README 里是这么写的）。
ACR_REGISTRY="${ACR_REGISTRY:-}"
ACR_USERNAME="${ACR_USERNAME:-}"
ACR_PASSWORD="${ACR_PASSWORD:-}"
GITHUB_REPO="${GITHUB_REPO:-}"
GITHUB_TOKEN="${GITHUB_TOKEN:-}"

ASKPASS_SCRIPT=""

# 本轮要镜像的镜像：每项 "源引用<TAB>目标引用<TAB>所在manifest"
PLAN_LINES=""
CHANGED_FILES=""

# ---------------------------------------------------------------- 日志

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
  C_BLU=$'\033[34m'; C_DIM=$'\033[2m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_DIM=""; C_RST=""
fi

log()  { printf '%s==>%s %s\n' "${C_BLU}" "${C_RST}" "$*"; }
ok()   { printf '%s  ok%s %s\n' "${C_GRN}" "${C_RST}" "$*"; }
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
  return 0
}
trap cleanup EXIT

# ---------------------------------------------------------------- 配置

load_env() {
  if [ -f "${ENV_FILE}" ]; then
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
  else
    die "未找到 ${ENV_FILE}。先 cp ${WF_DIR}/.env.example ${ENV_FILE} 并填 ACR_USERNAME / ACR_PASSWORD"
  fi
}

# 优先级：进程环境变量 > .env。与 deploy.sh 一致。
resolve_env() {
  [ -n "${ENV_ACR_REGISTRY}" ] && ACR_REGISTRY="${ENV_ACR_REGISTRY}"
  [ -n "${ENV_ACR_USERNAME}" ] && ACR_USERNAME="${ENV_ACR_USERNAME}"
  [ -n "${ENV_ACR_PASSWORD}" ] && ACR_PASSWORD="${ENV_ACR_PASSWORD}"
  [ -n "${ENV_GITHUB_REPO}" ] && GITHUB_REPO="${ENV_GITHUB_REPO}"
  [ -n "${ENV_GITHUB_TOKEN}" ] && GITHUB_TOKEN="${ENV_GITHUB_TOKEN}"
  return 0
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
  return 0
}

# GIT_ASKPASS：token 只落在临时文件里，不进 argv / ps 输出 / 日志。
# 不用 https://user:token@host 内嵌 URL —— 那种写法 token 会进 shell history 与进程列表。
setup_askpass() {
  [ -n "${ASKPASS_SCRIPT}" ] && return 0
  local cfg="${REPO_ROOT}/local-config/token.json"
  if [ -z "${GITHUB_TOKEN}" ]; then
    [ -f "${cfg}" ] || die "缺少 GitHub token：设置 GITHUB_TOKEN 环境变量，或准备 ${cfg}"
    GITHUB_TOKEN="$(node -p "require('${cfg}').github.token" 2>/dev/null || true)"
    [ -n "${GITHUB_TOKEN}" ] || die "无法从 ${cfg} 解析 github.token"
  fi
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
  # $1 = refspec，如 HEAD:main
  setup_askpass
  GIT_ASKPASS="${ASKPASS_SCRIPT}" GIT_TERMINAL_PROMPT=0 \
    git push "$(repo_url)" "$1" 2>&1 | sed 's/^/     /'
}

# ---------------------------------------------------------------- 参数

usage() {
  cat <<'EOF'
用法：./.local-workflow/mirror-middleware-images.sh [选项]

把 k8s 里拉不到的 postgres / redis 镜像从本地推到阿里云 ACR，并改写 k8s manifest。

选项：
  --dry-run              只打印将要做什么（不 pull/tag/push、不改文件、不提交）
  -y, --yes              跳过所有交互确认
  --only <list>          只处理指定镜像，逗号分隔（默认：k8s 里所有非 ACR 镜像）
  --skip-existing        ACR 已有相同 digest 则跳过推送
  --platform <p>         目标架构（默认：linux/amd64，k3s 节点就是 amd64）
  --no-pull              完全离线：只用本地镜像，架构不符直接报错（不自动重拉）
  --force-wrong-arch     明知架构不符也推（应急，默认禁止）
  --no-commit            只改 k8s 文件，不 commit & push
  -h, --help             显示本帮助
EOF
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run)       DRY_RUN=1 ;;
      -y|--yes)        ASSUME_YES=1 ;;
      --skip-existing) SKIP_EXISTING=1 ;;
      --no-commit)     NO_COMMIT=1 ;;
      --no-pull)       NO_PULL=1 ;;
      --force-wrong-arch) FORCE_WRONG_ARCH=1 ;;
      --only)          [ $# -ge 2 ] || die "--only 需要参数"; ONLY="$2"; shift ;;
      --only=*)        ONLY="${1#*=}" ;;
      --platform)      [ $# -ge 2 ] || die "--platform 需要参数"; PLATFORM="$2"; shift ;;
      --platform=*)    PLATFORM="${1#*=}" ;;
      -h|--help)       usage; exit 0 ;;
      *)               die "未知参数：$1（-h 看用法）" ;;
    esac
    shift
  done
  [ "${DRY_RUN}" -eq 1 ] && [ "${NO_COMMIT}" -eq 1 ] && die "--dry-run 与 --no-commit 同时给出没有意义"
  return 0
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

# ---------------------------------------------------------------- 镜像发现

# 判断引用里是否带了 registry host（docker 的规则）：
#   postgres:16-alpine          没有 '/' → Docker Hub 官方仓库，需要镜像源
#   myns/postgres:16-alpine     第一段无 '.'/':' → Docker Hub 用户命名空间，需要镜像源
#   docker.io/library/redis:7   第一段含 '.' → 是 registry
#   localhost:5000/redis:7      第一段含 ':' 或等于 localhost → 是 registry
#   crpi-xxx.cn-xxx.aliyuncs.com/ns/img:tag → 是 registry
has_registry_host() {
  local ref="$1" first
  case "${ref}" in
    */*) first="${ref%%/*}" ;;
    *)   return 1 ;;   # 没有路径分隔符 ⇒ 只能是 Docker Hub 官方镜像
  esac
  case "${first}" in
    *.*|*:*) return 0 ;;
    localhost|localhost.localdomain) return 0 ;;
    *) return 1 ;;
  esac
}

image_name_and_tag() {
  local ref="$1" name tag
  name="$(printf '%s' "${ref}" | sed -e 's|.*/||' -e 's|:.*||')"
  tag="$(printf '%s' "${ref}" | sed -n 's|.*:||p')"
  [ -n "${tag}" ] || tag="latest"
  printf '%s %s' "${name}" "${tag}"
}

# 扫 k8s/*.yaml，收集「不带 registry host」的 image 行。
# 结果每行：<源引用>\t<manifest 路径>\t<行号>
discover_images() {
  local manifest line ref
  for manifest in "${K8S_DIR}"/*.yaml "${K8S_DIR}"/*.yml; do
    [ -f "${manifest}" ] || continue
    line=0
    while IFS= read -r raw; do
      line=$((line + 1))
      # 先确认这行确实是 image: 字段，再取值。
      # 注意取值那步不能用 sed -n —— -n 抑制自动打印，而 s/// 本身不会打印（要显式加 p）。
      printf '%s\n' "${raw}" | grep -qE '^[[:space:]]*image:[[:space:]]*[^[:space:]]' || continue
      ref="$(printf '%s\n' "${raw}" \
        | sed -e 's|^[[:space:]]*image:[[:space:]]*||' -e 's|".*$||' -e 's|[[:space:]]*$||')"
      [ -n "${ref}" ] || continue
      has_registry_host "${ref}" && continue
      # --only 过滤
      if [ -n "${ONLY}" ]; then
        local nt name
        nt="$(image_name_and_tag "${ref}")"
        name="${nt%% *}"
        case " ${ONLY//,/ } " in
          *" ${name} "*) : ;;
          *) continue ;;
        esac
      fi
      PLAN_LINES="${PLAN_LINES}${ref}	${manifest}	${line}
"
    done < "${manifest}"
  done
  printf '%s' "${PLAN_LINES}"
}

# ---------------------------------------------------------------- 镜像操作

local_arch() {
  # docker image inspect 对「本地没有该 tag」返回非 0，这里用 || true 兜住
  docker image inspect --format '{{.Architecture}}' "$1" 2>/dev/null || true
}

remote_arch_of_local() {
  # 从本地镜像的 RepoDigests 里找 digest；用来判断「本地这份是不是重打过标签的旧版本」
  docker image inspect --format '{{join .RepoDigests " "}}' "$1" 2>/dev/null || true
}

target_arch_name() {
  # linux/amd64 → amd64；linux/arm/v7 → arm（docker inspect 报的是 arm）
  case "${PLATFORM}" in
    linux/arm/v*) printf 'arm' ;;
    */*)          printf '%s' "${PLATFORM#*/}" ;;
    *)            printf '%s' "${PLATFORM}" ;;
  esac
}

# 推送前架构硬校验：不符则按目标平台重拉；拉不到就中止，绝不推错架构上去。
ensure_platform() {
  local src="$1" want have
  want="$(target_arch_name)"
  have="$(local_arch "${src}")"

  if [ "${have}" = "${want}" ]; then
    ok "${src} 本地架构 ${have} ✓"
    return 0
  fi

  if [ -z "${have}" ]; then
    warn "${src} 本地没有这个镜像，先拉取"
  else
    warn "${src} 本地架构是 ${have}，k3s 节点要 ${want} —— 直接推会让线上 exec format error"
  fi

  if [ "${NO_PULL}" -eq 1 ]; then
    die "--no-pull 指定了只用本地镜像，但本地没有 ${want} 版本的 ${src}。去掉 --no-pull 让脚本重拉，或手动 docker pull --platform ${PLATFORM} ${src}"
  fi
  if [ "${FORCE_WRONG_ARCH}" -eq 1 ]; then
    warn "--force-wrong-arch：明知架构不符仍然推送 ${src}（${have} → 线上要 ${want}）"
    return 0
  fi

  log "拉取 ${PLATFORM} 版本：${src}"
  docker pull --platform "${PLATFORM}" "${src}" || die "拉取 ${PLATFORM} 版 ${src} 失败（Docker Hub 不可达？）。节点拉不到镜像时本机往往也拉不到 —— 换一台有网络的机器跑本脚本，或用 --platform 显式指定。"
  have="$(local_arch "${src}")"
  [ "${have}" = "${want}" ] || die "重拉后 ${src} 架构仍是 ${have}（期望 ${want}）。Docker Desktop 若是经典镜像存储（非 containerd），同一 tag 只能留一个架构变体，请改用 --platform ${want} 在 amd64 机器上跑本脚本。"
  ok "${src} 已切换到 ${have}"
  return 0
}

# 推送后复核：ACR 里这个 tag 到底是不是目标架构。
verify_pushed_arch() {
  local target="$1" want out
  want="$(target_arch_name)"
  out="$(docker buildx imagetools inspect "${target}" 2>/dev/null || true)"
  if [ -z "${out}" ]; then
    warn "无法用 imagetools inspect 校验 ${target}（buildx 版本或网络问题）。请人工确认：docker buildx imagetools inspect ${target}"
    return 0
  fi
  if printf '%s' "${out}" | grep -qi "${want}"; then
    ok "${target} 已确认含 ${want}"
  else
    err "${target} 里没找到 ${want} 架构！线上 Pod 会 exec format error。"
    die "架构校验失败。先确认 k3s 节点架构（kubectl get nodes -o wide），再用正确的 --platform 重推。"
  fi
  return 0
}

remote_digest() {
  docker buildx imagetools inspect "$1" 2>/dev/null \
    | sed -n 's/^Digest:[[:space:]]*//p' | head -1
}

# ACR 403 的真实原因通常是「命名空间下还没有这个镜像仓库」，翻译成能照做的提示。
explain_push_failure() {
  local target="$1" log_file="$2"
  if grep -qiE 'denied|403|unauthorized|forbidden' "${log_file}" 2>/dev/null; then
    err "ACR 拒绝了推送。最常见原因：命名空间 $(printf '%s' "${target}" | cut -d/ -f2) 下还没有这个镜像仓库。"
    err "阿里云 ACR 要求镜像仓库先在控制台创建： ACR 控制台 → 命名空间 ${ACR_REGISTRY##*/} → 新建镜像仓库 → 名称取 $(printf '%s' "${target}" | sed -e 's|.*/||' -e 's|:.*||')（仓库类型选「本地仓库」）"
    err "若仓库已存在则检查 ACR_USERNAME/ACR_PASSWORD 是否有该命名空间的 push 权限。"
  fi
  return 0
}

mirror_one() {
  local src="$1" target="$2" manifest="$3" nt name tag push_log
  hr
  log "镜像 ${src} → ${target}"
  nt="$(image_name_and_tag "${src}")"
  name="${nt%% *}"
  tag="${nt##* }"

  if [ "${DRY_RUN}" -eq 0 ]; then
    # 先判跳过，再考虑架构：已经决定跳过就不该白拉一次镜像
    if [ "${SKIP_EXISTING}" -eq 1 ]; then
      local existing
      existing="$(remote_digest "${target}")"
      if [ -n "${existing}" ]; then
        ok "ACR 已有 ${target}（${existing}），--skip-existing 跳过推送"
        return 0
      fi
    fi

    ensure_platform "${src}"

    # 本地这份与上游同 tag 是否一致：不一致说明本地是重打过标签的旧版本，换源后线上就跑那个旧版本
    local digests
    digests="$(remote_arch_of_local "${src}")"
    if [ -n "${digests}" ]; then
      dim "本地 digest：${digests}"
    fi

    docker tag "${src}" "${target}" || die "打标签失败：${src} → ${target}"
    log "推送（镜像不小，进度可能要走一会儿）"
    push_log="${OUTPUT_DIR}/mirror-push-${name}-${tag}.log"
    if ! docker push "${target}" > "${push_log}" 2>&1; then
      err "推送失败，末尾输出："
      tail -20 "${push_log}" | sed 's/^/     /' >&2
      explain_push_failure "${target}" "${push_log}"
      exit 1
    fi
    ok "推送完成 ${target}"
    verify_pushed_arch "${target}"
  else
    dim "将执行： docker tag ${src} ${target} && docker push ${target}"
    dim "推送前校验本地架构是否为 $(target_arch_name)"
  fi
  return 0
}

# ---------------------------------------------------------------- manifest 改写

rewrite_manifest() {
  local manifest="$1" src="$2" target="$3"
  [ "${DRY_RUN}" -eq 0 ] || return 0
  # \1 保留原有缩进（不写死 8 空格）；只替换 image 引用的值，不动 imagePullPolicy / 其余字段。
  # src 里的 ':' '.' '/' 在 BRE 里都不是元字符，但 '|' 是 —— 这里用 '|' 作分隔符前先转义。
  local esc_src
  esc_src="$(printf '%s' "${src}" | sed -e 's/[\\^$.*[]|]/\\&/g')"
  sed "${SED_I[@]}" "s|^\([[:space:]]*\)image:[[:space:]]*${esc_src}[[:space:]]*\$|\1image: ${target}|" "${manifest}"
  return 0
}

apply_manifest_changes() {
  local manifest
  for manifest in ${CHANGED_FILES}; do
    [ -n "${manifest}" ] || continue
    if ! grep -q "${ACR_REGISTRY}/" "${manifest}"; then
      die "${manifest} 改写后仍未指向 ACR，请检查"
    fi
  done
  ok "k8s manifest 已改写并自检通过"
  return 0
}

# ---------------------------------------------------------------- 提交

commit_and_push() {
  if [ -z "${CHANGED_FILES}" ]; then
    warn "k8s 文件无变化，无需提交"
    return 0
  fi
  if [ "${NO_COMMIT}" -eq 1 ]; then
    warn "--no-commit：只改文件，不提交"
    return 0
  fi
  if [ "${DRY_RUN}" -eq 1 ]; then
    dim "（dry-run）将执行：git add ${CHANGED_FILES} && git commit && git push origin main"
    return 0
  fi

  log "提交并推送 k8s 镜像地址（Fleet 靠这个 commit 自动 apply）"
  confirm "确认 push 到 origin/main？" || die "已取消"
  # shellcheck disable=SC2086
  git add ${CHANGED_FILES}
  git commit -m "chore: mirror middleware images to ACR [skip ci]" >/dev/null

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

# ---------------------------------------------------------------- 主流程

step_preflight() {
  log "preflight"
  local tool missing=0
  for tool in git docker sed grep node; do
    if command -v "${tool}" >/dev/null 2>&1; then
      dim "${tool} ok"
    else
      err "缺少 ${tool}"
      missing=1
    fi
  done
  [ "${missing}" -eq 0 ] || exit 1

  command -v docker >/dev/null 2>&1 && docker buildx version >/dev/null 2>&1 \
    || warn "buildx 不可用，推送后的架构复核会退化为警告"

  [ -n "${ACR_USERNAME}" ] && [ -n "${ACR_PASSWORD}" ] || die "未配置 ACR_USERNAME / ACR_PASSWORD"
  [ -n "${ACR_REGISTRY}" ] || die "未配置 ACR_REGISTRY"
  ok "ACR ${ACR_REGISTRY}（账号 ${ACR_USERNAME}）"

  local branch
  branch="$(git rev-parse --abbrev-ref HEAD)"
  [ "${branch}" = "main" ] || die "当前分支是 ${branch}，请先切到 main（k8s manifest 会被直接推上去）"
  if [ -n "$(git status --porcelain)" ]; then
    git status --short | sed 's/^/     /' >&2
    die "工作树不干净。请先 commit 或 stash —— 本脚本会改 k8s 并直接 push main"
  fi
  ok "分支 main，工作树干净"
  return 0
}

step_login() {
  log "登录 ACR ${ACR_REGISTRY}"
  if [ "${DRY_RUN}" -eq 1 ]; then
    dim "docker login ${ACR_REGISTRY} -u <ACR_USERNAME> --password-stdin（跳过）"
    return 0
  fi
  # --password-stdin：密码不进 argv
  printf '%s' "${ACR_PASSWORD}" | docker login "${ACR_REGISTRY}" \
    --username "${ACR_USERNAME}" --password-stdin >/dev/null 2>&1 \
    || die "登录 ACR 失败：检查 ACR_USERNAME / ACR_PASSWORD（阿里云控制台 → 访问凭证）"
  ok "已登录 ${ACR_REGISTRY}"
  return 0
}

summary() {
  hr
  log "汇总"
  local src target manifest nt
  while IFS="$(printf '\t')" read -r src target manifest; do
    [ -n "${src}" ] || continue
    printf '     %-28s → %s\n' "${src}" "${target}"
    printf '       %s\n' "${manifest}"
  done << EOF
$(plan_rows)
EOF
  [ -n "${CHANGED_FILES}" ] && [ "${DRY_RUN}" -eq 0 ] && dim "已改写：${CHANGED_FILES}"
  if [ "${DRY_RUN}" -eq 0 ]; then
    echo
    dim "未加 imagePullPolicy（保持最小 diff）：节点默认 IfNotPresent，"
    dim "以后重推同一个 tag 不会自动生效，需要 kubectl -n agent-sphere rollout restart deploy/postgres"
    dim "验证：kubectl -n agent-sphere get pods -l app=postgres -o wide"
  fi
  echo
  return 0
}

# 把 PLAN_LINES（源/目标/manifest）打印成可读表格
plan_rows() {
  local line src target manifest
  while IFS="$(printf '\t')" read -r src manifest line; do
    [ -n "${src}" ] || continue
    nt="$(image_name_and_tag "${src}")"
    target="${ACR_REGISTRY}/$(printf '%s' "${nt}" | tr ' ' ':')"
    printf '%s\t%s\t%s\n' "${src}" "${target}" "${manifest}"
  done << EOF
${PLAN_LINES}
EOF
  return 0
}

main() {
  trap on_err ERR
  parse_args "$@"
  load_env
  resolve_env
  mkdir -p "${OUTPUT_DIR}"
  LOG_FILE="${OUTPUT_DIR}/mirror-$(date +%Y%m%d-%H%M%S).log"
  exec > >(tee -a "${LOG_FILE}") 2>&1
  if [ "${DRY_RUN}" -eq 1 ]; then
    warn "dry-run：不会 pull/tag/push、不改文件、不提交"
  else
    log "日志 ${LOG_FILE}"
  fi

  step_preflight

  # 先发现再要凭据：已经全部指向 ACR 时（幂等重跑）不该逼用户准备 token，也不该白登录一次 ACR
  PLAN_LINES="$(discover_images)"
  if [ -z "${PLAN_LINES//[[:space:]]/}" ]; then
    ok "${K8S_DIR}/ 里已没有需要镜像的镜像（image 行全部带 registry host）。无事可做。"
    if [ -n "${ONLY}" ]; then
      dim "--only ${ONLY} 指定的镜像都已指向 ACR。若镜像名写错了，检查 k8s/ 里的 image 名。"
    fi
    return 0
  fi

  # 镜像推上去要好几分钟，不能等推完才发现没法提交
  if [ "${NO_COMMIT}" -eq 0 ] && [ "${DRY_RUN}" -eq 0 ]; then
    resolve_github_token \
      || die "缺少 GitHub token：设置 GITHUB_TOKEN 环境变量，或准备 local-config/token.json"
  fi
  step_login

  log "待镜像清单"
  plan_rows | while IFS="$(printf '\t')" read -r src target manifest; do
    [ -n "${src}" ] || continue
    dim "${src}  →  ${target}   (${manifest})"
  done

  local src target manifest nt
  while IFS="$(printf '\t')" read -r src target manifest; do
    [ -n "${src}" ] || continue
    nt="$(image_name_and_tag "${src}")"
    target="${ACR_REGISTRY}/$(printf '%s' "${nt}" | tr ' ' ':')"
    mirror_one "${src}" "${target}" "${manifest}"
    rewrite_manifest "${manifest}" "${src}" "${target}"
    case " ${CHANGED_FILES} " in
      *" ${manifest} "*) : ;;
      *) CHANGED_FILES="${CHANGED_FILES} ${manifest}" ;;
    esac
  done << EOF
$(plan_rows)
EOF

  if [ "${DRY_RUN}" -eq 0 ]; then
    apply_manifest_changes
    git --no-pager diff --stat -- "${K8S_DIR}" | sed 's/^/     /'
  fi
  commit_and_push
  summary
}

main "$@"
