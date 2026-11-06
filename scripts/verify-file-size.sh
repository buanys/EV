#!/bin/sh
# 单文件行数门禁：主代码 400 行上限。
#
# 为什么是 400：不是因为 400 这个数字有魔力，而是因为超过它之后，文件开始同时回答
# 好几个问题——"这段配哪个回调"、"那个状态从哪来"、"失败怎么处理"混在一起。
# 枢纽类就是这样长出来的（HostRuntime 1050 行之前也是从 300 行开始的）。
#
# 白名单是**待办清单**，不是豁免：只减不增。新文件超限直接失败；
# 白名单里的文件缩到 400 以下时，脚本会提醒把它移出白名单。

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

LIMIT=400
ALLOWLIST="scripts/architecture-allowlist.txt"

[ -f "$ALLOWLIST" ] || { echo "找不到 $ALLOWLIST" >&2; exit 1; }

# 白名单：一行一个路径，# 开头是注释
allowed=$(sed -e 's/#.*//' -e 's/[[:space:]]*$//' "$ALLOWLIST" | grep -v '^$' || true)

fail=0
over=""

for f in $(git ls-files '*/src/main/**/*.kt'); do
    # index 里还留着刚删掉、尚未提交的文件：跳过，否则这里的 set -e 会让门禁
    # 在"删了一个文件但还没 add"的中间状态直接报错，而不是报它真正要说的事。
    [ -f "$f" ] || continue
    lines=$(wc -l <"$f")
    [ "$lines" -gt "$LIMIT" ] || continue
    if printf '%s\n' "$allowed" | grep -qxF "$f"; then
        over="$over
  $lines  $f  (白名单)"
    else
        fail=1
        over="$over
  $lines  $f  ← 超限，且不在白名单"
    fi
done

if [ "$fail" -eq 1 ]; then
    echo "FAIL：有文件超过 $LIMIT 行且不在白名单里" >&2
    printf '%s\n' "$over" >&2
    echo "处理方式：拆文件，或者（如果确有理由）加进 $ALLOWLIST 并在同一提交里说明。" >&2
    exit 1
fi

echo "OK：没有新增的超限文件"
printf '%s\n' "$over" | sed '/^$/d' | sed 's/^/  剩余白名单:/' || true

# 白名单本身要诚实：文件已经不到 400 行，或已经不存在，都应该从白名单里删掉。
stale=""
if [ -n "$allowed" ]; then
    for f in $allowed; do
        if [ ! -f "$f" ]; then
            stale="$stale
  不存在: $f"
        elif [ "$(wc -l <"$f")" -le "$LIMIT" ]; then
            stale="$stale
  已达标: $f（$(wc -l <"$f") 行）→ 可以移出白名单"
        fi
    done
fi
if [ -n "$stale" ]; then
    echo "注意：白名单需要更新" >&2
    printf '%s\n' "$stale" >&2
fi
