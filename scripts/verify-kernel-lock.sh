#!/bin/sh
# 内核冻结门禁。
#
# 只做一件事：重算 third_party/cordis 的目录树哈希，和 KERNEL.lock 里记的比。
# 不一致就失败——这表示内核被就地改过。内核改动只能先在上游分支完成，再同步回来、
# 更新 KERNEL.lock 并人工审核（见 docs/newdocs/最高规则 §四.2）。
#
# 哈希算法与 KERNEL.lock 里的 tree_hash_algorithm 必须逐字一致；算法改了就等于门禁改了。

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
KERNEL="$ROOT/third_party/cordis"
LOCK="$KERNEL/KERNEL.lock"

[ -d "$KERNEL" ] || { echo "找不到 $KERNEL" >&2; exit 1; }
[ -f "$LOCK" ] || { echo "找不到 $LOCK" >&2; exit 1; }

actual=$(
    cd "$KERNEL"
    find core loader -type f \
        ! -path '*/build/*' ! -path '*/.gradle/*' -print \
        | LC_ALL=C sort \
        | while IFS= read -r f; do
              printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
          done \
        | sha256sum | cut -d' ' -f1
)

expected=$(sed -n 's/^tree_sha256 = //p' "$LOCK" | tr -d '\r')

echo "actual   = $actual"
echo "expected = $expected"

if [ "$actual" = "$expected" ]; then
    echo "OK：内核与 KERNEL.lock 一致"
else
    echo "FAIL：内核被就地改动过。改动请先走上游，再同步并更新 KERNEL.lock。" >&2
    exit 1
fi
