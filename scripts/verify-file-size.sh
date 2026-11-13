#!/bin/sh
# 单文件行数门禁：主代码 400 行上限。
#
# 为什么是 400：不是因为 400 这个数字有魔力，而是因为超过它之后，文件开始同时回答
# 好几个问题——"这段配哪个回调"、"那个状态从哪来"、"失败怎么处理"混在一起。
# 枢纽类就是这样长出来的（HostRuntime 1050 行之前也是从 300 行开始的）。
#
# 白名单是**待办清单**，不是豁免：**只减不增**。所以每个条目必须带一个上限
# （`路径 最大行数`），文件比登记时更长就直接失败——只写路径等于给它无限豁免，
# 那是"挂名"，不是门禁（2026-11-07 由外部评审点出，这一版改掉）。
#
# 三种需要处理的提示：
#   - 文件缩到 400 行以下 → 从白名单删掉；
#   - 文件比登记上限短 → 把上限收紧到当前行数（re-ratchet）；
#   - 文件比登记上限长 → 失败：拆文件，或者在一个提交里说明为什么必须变长、
#     并把上限调高（评审会看见这行改动）。

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

LIMIT=400
ALLOWLIST="scripts/architecture-allowlist.txt"

[ -f "$ALLOWLIST" ] || { echo "找不到 $ALLOWLIST" >&2; exit 1; }

# 白名单行：`路径 最大行数`，# 之后是注释。返回命中的那一行（没有就空）。
allow_entry() {
    sed -e 's/#.*//' "$ALLOWLIST" \
        | awk -v p="$1" 'NF >= 1 && $1 == p { print; exit }'
}

fail=0
over=""
ratchet=""

for f in $(git ls-files '*/src/main/**/*.kt'); do
    # index 里还留着刚删掉、尚未提交的文件：跳过，否则这里的 set -e 会让门禁
    # 在"删了一个文件但还没 add"的中间状态直接报错，而不是报它真正要说的事。
    [ -f "$f" ] || continue
    lines=$(wc -l <"$f")
    [ "$lines" -gt "$LIMIT" ] || continue
    entry=$(allow_entry "$f")
    if [ -z "$entry" ]; then
        fail=1
        over="$over
  $lines  $f  ← 超限，且不在白名单"
        continue
    fi
    max=$(printf '%s\n' "$entry" | awk '{print $2}')
    case "$max" in
        ''|*[!0-9]*)
            fail=1
            over="$over
  $lines  $f  ← 白名单条目缺少行数上限（写成「路径 最大行数」）"
            continue
            ;;
    esac
    if [ "$lines" -gt "$max" ]; then
        fail=1
        over="$over
  $lines  $f  ← 白名单文件只能变短：登记上限 $max，现在 $lines 行"
    else
        over="$over
  $lines  $f  (白名单，上限 $max)"
        [ "$lines" -lt "$max" ] && ratchet="$ratchet
  $f：上限 $max → 可以收紧到 $lines"
    fi
done

if [ "$fail" -eq 1 ]; then
    echo "FAIL：文件行数门禁" >&2
    printf '%s\n' "$over" >&2
    echo "处理方式：拆文件；或（如果确有理由）把行数上限调高并在同一提交里说明。" >&2
    exit 1
fi

echo "OK：没有新增的超限文件"
printf '%s\n' "$over" | sed '/^$/d' | sed 's/^/  剩余白名单:/' || true

# 白名单本身要诚实：文件不存在、已经达标、或者比登记上限短，都应该更新。
stale=""
sed -e 's/#.*//' "$ALLOWLIST" | while read -r line; do
    [ -n "$line" ] || continue
    f=$(printf '%s\n' "$line" | awk '{print $1}')
    max=$(printf '%s\n' "$line" | awk '{print $2}')
    if [ ! -f "$f" ]; then
        printf '  不存在: %s\n' "$f"
    else
        now=$(wc -l <"$f")
        if [ "$now" -le "$LIMIT" ]; then
            printf '  已达标: %s（%s 行）→ 可以移出白名单\n' "$f" "$now"
        elif [ -n "$max" ] && [ "$now" -lt "$max" ]; then
            printf '  %s：上限 %s → 可以收紧到 %s\n' "$f" "$max" "$now"
        fi
    fi
done > /tmp/ev-allowlist-stale.$$ 2>/dev/null || true

if [ -s /tmp/ev-allowlist-stale.$$ ]; then
    echo "注意：白名单需要更新" >&2
    cat /tmp/ev-allowlist-stale.$$ >&2
fi
rm -f /tmp/ev-allowlist-stale.$$

if [ -n "$ratchet" ]; then
    echo "注意：这些白名单条目可以收紧上限" >&2
    printf '%s\n' "$ratchet" | sed '/^$/d' >&2
fi
