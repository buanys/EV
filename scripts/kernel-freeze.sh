#!/usr/bin/env bash
# EV 内核冻结门禁
#
# 内核目录（loader core hmr include packages）在 EV 里只当依赖用，不接受业务改动。
# 本脚本把内核目录与基线提交比对：出现白名单
# （gradle/kernel-freeze-allowlist.txt）之外的文件即失败，退出码 1。
#
# 用法：
#   scripts/kernel-freeze.sh                    # 基线 = 本仓库根提交
#   scripts/kernel-freeze.sh --baseline <ref>   # 临时指定基线（排查用）
#   KERNEL_FREEZE_BASELINE=<ref> scripts/kernel-freeze.sh
#
# 前提：基线提交必须在本地对象库里。CI 用浅克隆（fetch-depth: 1）时基线不存在，
# 脚本会直接报错退出而不是静默通过。
set -euo pipefail
cd "$(dirname "$0")/.."

# 基线默认取本仓库根提交，不写死哈希：历史一旦被重写（rebase / filter-branch），
# 任何硬编码的提交都会消失，门禁会以"找不到对象"的方式坏掉，而不是继续保护内核。
BASELINE=${KERNEL_FREEZE_BASELINE:-$(git rev-list --max-parents=0 HEAD | tail -1)}
while [[ $# -gt 0 ]]; do
  case $1 in
    --baseline)
      [[ $# -ge 2 ]] || { echo "缺少 --baseline 的值" >&2; exit 2; }
      BASELINE=$2
      shift 2
      ;;
    --baseline=*)
      BASELINE=${1#--baseline=}
      shift
      ;;
    -h|--help)
      awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

ALLOWLIST=gradle/kernel-freeze-allowlist.txt
KERNEL_PATHS=(loader core hmr include packages)

[[ -f $ALLOWLIST ]] || { echo "缺少白名单 $ALLOWLIST" >&2; exit 1; }
git rev-parse --verify --quiet "${BASELINE}^{commit}" >/dev/null ||
  { echo "基线提交 $BASELINE 不在本地对象库里（CI 请用 fetch-depth: 0 完整克隆）" >&2; exit 1; }

# 白名单：一行一个 shell case 模式，# 之后是注释。命中的路径算「允许的例外」。
allowed() {
  local path=$1 line
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'} # 白名单在 Windows 上被编辑器存成 CRLF 也要能匹配
    line=${line%%#*}
    line=${line#"${line%%[![:space:]]*}"}
    line=${line%"${line##*[![:space:]]}"}
    [[ -z $line ]] && continue
    # 模式要按 glob 展开，不能当字面量。
    # shellcheck disable=SC2254
    case $path in
      $line) return 0 ;;
    esac
  done <"$ALLOWLIST"
  return 1
}

# 内核差异 = 相对基线的已跟踪文件改动 + 内核目录下的未跟踪文件（git diff 看不到后者）。
# 显式打开 rename 识别：EV 把上游 androidUnitTest 的测试移到 androidHostTest 是一处
# 完整性保留的移动（R100），按目标路径参与白名单；移动出内核、或从外部移入内核，
# 目标/来源路径里总有一侧落在受保护目录且不在白名单内，仍然判失败。
kernel_changes() {
  git diff --name-only --find-renames "$BASELINE" -- "${KERNEL_PATHS[@]}"
  git ls-files --others --exclude-standard -- "${KERNEL_PATHS[@]}"
}

changed=$(kernel_changes | sed '/^$/d' | sort -u)

violations=()
exceptions=()
while IFS= read -r file; do
  [[ -z $file ]] && continue
  if allowed "$file"; then
    exceptions+=("$file")
  else
    violations+=("$file")
  fi
done <<<"$changed"

echo "内核冻结门禁：基线 $BASELINE，受保护目录 ${KERNEL_PATHS[*]}"

if (( ${#exceptions[@]} > 0 )); then
  echo "白名单例外（允许）："
  printf '  %s\n' "${exceptions[@]}"
fi

if (( ${#violations[@]} > 0 )); then
  {
    echo
    echo "失败：${#violations[@]} 个内核文件相对基线有改动，且不在白名单内："
    printf '  %s\n' "${violations[@]}"
    echo
    echo "内核在 EV 只能当依赖用。属于构建/测试接线的改动，请先在 $ALLOWLIST"
    echo "登记并在评审里说明理由；其余改动必须回退。"
  } >&2
  exit 1
fi

echo "通过：内核相对 $BASELINE 没有白名单之外的改动。"
