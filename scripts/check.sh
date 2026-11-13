#!/bin/sh
# 架构门禁总入口。三条都过才算通过：
#
#   1. 内核冻结   —— third_party/cordis 的目录树哈希 == KERNEL.lock
#   2. 模块边界   —— 插件不依赖宿主，宿主不依赖插件
#   3. 文件行数   —— 主代码单文件 ≤ 400 行（白名单只减不增）
#
# 用法：sh scripts/check.sh    （CI 与提交前都跑这一个）

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

status=0
for s in verify-kernel-lock verify-module-boundaries verify-file-size; do
    echo "── $s"
    if ! sh "scripts/$s.sh"; then
        status=1
    fi
    echo
done

if [ "$status" -eq 0 ]; then
    echo "架构门禁：全部通过"
else
    echo "架构门禁：有失败项（见上）" >&2
fi
exit "$status"
