#!/usr/bin/env bash
# forge 载荷构建：把 Kotlin 编译器 / d8 / trove / annotations 变成 EV 能加载的能力包内容。
#
# 两种模式：
#   --from-probe <jar>   从一次已验证的探针产物里搬运载荷（Tier 1，当前可用）。
#                        来源与产物哈希记进 payload.json：可复现，但不算自举。
#   --from-source        （Tier 2，未实现）从 Gradle 缓存的 jar 重新 dex 化 + ASM 打补丁重建。
#                        缺什么见 docs/adr/0004-on-device-forge.md 待办 1。
#
# 用法：
#   scripts/forge-payload.sh --from-probe /sdcard/EV/mods/compileprobe.jar --out plugins/forge-compiler/payload
set -euo pipefail

MODE=""
PLUGIN_API=""
SRC=""
OUT="plugins/forge-compiler/payload"

while [ $# -gt 0 ]; do
  case "$1" in
    --from-probe) MODE=probe; SRC="${2:?--from-probe 需要一个 jar 路径}"; shift 2 ;;
    --from-source) MODE=source; shift ;;
    --plugin-api) PLUGIN_API="${2:?--plugin-api 需要 plugin-api 的 classes.jar}"; shift 2 ;;
    --out) OUT="${2:?--out 需要一个目录}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

[ -n "$MODE" ] || { echo "必须给 --from-probe <jar> 或 --from-source" >&2; exit 2; }

if [ "$MODE" = source ]; then
  cat >&2 <<'EOF'
--from-source 尚未实现。缺三样（见 docs/adr/0004-on-device-forge.md 待办 1）：
  1. trove4j:1.0.20200330 —— Gradle 缓存里没有，需要联网取一次；
  2. 两个 ASM 补丁：PerformanceManager.initializeCurrentThread 只保留 thread 赋值、
     PathUtil 的资源查找直接返回 NO_PATH 哨兵；
  3. android-stub.jar —— 从 android.jar 抽 java.* 共 1160 个类。
先用 --from-probe 把链路打通，P1b 再把这里换成自举。
EOF
  exit 3
fi

[ -f "$SRC" ] || { echo "找不到探针产物：$SRC" >&2; exit 2; }

sha() { sha256sum "$1" | cut -d' ' -f1; }

# 容器的 /usr/bin/unzip 是断链（dpkg 残留），所以解包按能力回退，而不是假定某个工具存在。
extract() {
  local zip="$1" dest="$2"
  if command -v unzip >/dev/null 2>&1 && unzip -q "$zip" -d "$dest" 2>/dev/null; then return 0; fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$zip" "$dest" <<'PY'
import sys, zipfile
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
PY
    return 0
  fi
  if command -v jar >/dev/null 2>&1; then (cd "$dest" && jar xf "$zip"); return 0; fi
  echo "没有可用的解包工具（unzip / python3 / jar 都不可用）" >&2
  return 1
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
extract "$SRC" "$WORK"

rm -rf "$OUT"
mkdir -p "$OUT/dex" "$OUT/lib" "$OUT/META-INF/services"

# 1. dex：探针里 classes*.dex 按序号重排；classes.dex 留给插件自己的类（打包时排在最前）
i=0
for f in $(ls "$WORK" | grep -E '^classes[0-9]*\.dex$' |
           awk '{ n=$0; sub(/^classes/,"",n); sub(/\.dex$/,"",n); print (n==""?1:n), $0 }' |
           sort -n | awk '{print $2}'); do
  i=$((i+1))
  cp "$WORK/$f" "$OUT/dex/payload-classes$i.dex"
done

# 2. 编译期 classpath 资源：编译器要在 ART 上"读得到"这些 jar
for lib in core.jar plugin-api.jar kotlin-stdlib.jar android-stub.jar annotations.jar; do
  if [ -f "$WORK/$lib" ]; then cp "$WORK/$lib" "$OUT/lib/$lib"; else echo "警告：探针里没有 $lib" >&2; fi
done

# 3. 编译器启动要读的清单资源（研究文档 §4.5 / §4.6）
[ -f "$WORK/META-INF/compiler.version" ] && cp "$WORK/META-INF/compiler.version" "$OUT/META-INF/"
if [ -d "$WORK/META-INF/services" ]; then
  find "$WORK/META-INF/services" -type f | while read -r s; do
    cp "$s" "$OUT/META-INF/services/$(basename "$s")"
  done
fi

# 4. 契约 jar 用**当前构建**的那一份覆盖探针里的旧拷贝。
#    探针冻结在 2026-10-27：kernel 冻结，所以 core.jar 可以沿用；但 plugin-api 会加新契约
#    （例如 PluginInstallService），拿旧的那份去编，生成出来的插件根本看不到新接口。
if [ -n "$PLUGIN_API" ]; then
  [ -f "$PLUGIN_API" ] || { echo "找不到 plugin-api classes.jar：$PLUGIN_API" >&2; exit 2; }
  cp "$PLUGIN_API" "$OUT/lib/plugin-api.jar"
  echo "plugin-api.jar 已用当前构建覆盖（$(wc -c < "$OUT/lib/plugin-api.jar" | tr -d ' ') 字节）"
fi

# 5. 清单：来源与哈希，便于核对"装到设备上的到底是不是这份"
{
  echo "{"
  echo "  \"source\": \"$SRC\","
  echo "  \"sourceSha256\": \"$(sha "$SRC")\","
  echo "  \"dexCount\": $i,"
  echo "  \"libs\": ["
  first=1
  for f in "$OUT"/lib/*.jar; do
    [ -f "$f" ] || continue
    [ $first -eq 1 ] || echo ","
    first=0
    printf '    {"name": "%s", "bytes": %s, "sha256": "%s"}' \
      "$(basename "$f")" "$(wc -c < "$f" | tr -d ' ')" "$(sha "$f")"
  done
  echo
  echo "  ]"
  echo "}"
} > "$OUT/payload.json"
