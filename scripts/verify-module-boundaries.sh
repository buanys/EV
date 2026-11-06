#!/bin/sh
# 模块边界门禁。
#
# 规则：插件只能依赖契约（:plugin-api，将来叫 :host-api）和设计系统（:ui-kit），
# 不能依赖宿主（:host / :host-core / :host-runtime / :host-app）；宿主也不能反向依赖插件。
#
# 这两条一旦破，插件就变成了宿主的一部分：换不了、卸不掉、也没法单独测试。
# 所以不靠评审，靠脚本。

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

fail=0

# 1) 插件 → 宿主：禁止
hits=$(grep -rn --include=build.gradle.kts -E 'project\(":(host|host-core|host-runtime|host-app)("|:)' plugins 2>/dev/null || true)
if [ -n "$hits" ]; then
    echo "FAIL：插件依赖了宿主内部模块" >&2
    echo "$hits" >&2
    fail=1
else
    echo "OK：没有插件依赖宿主"
fi

# 2) 宿主 → 插件：禁止
hits=$(grep -rn --include=build.gradle.kts -E 'project\(":plugins:' host host-core 2>/dev/null || true)
if [ -n "$hits" ]; then
    echo "FAIL：宿主依赖了某个插件" >&2
    echo "$hits" >&2
    fail=1
else
    echo "OK：宿主没有依赖插件"
fi

# 3) 插件里直接 import 宿主包名：禁止（编译期抓不到的情况，比如反射/字符串）
hits=$(grep -rn --include=*.kt -E '^import ev\.buan\.(HostRuntime|HostState|PluginInstaller|PluginScanCache)' plugins 2>/dev/null || true)
if [ -n "$hits" ]; then
    echo "FAIL：插件 import 了宿主实现类" >&2
    echo "$hits" >&2
    fail=1
fi

exit $fail
