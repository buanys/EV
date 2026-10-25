#!/usr/bin/env bash
# EV release 冒烟（E16）
#
# 构建 release + 门禁 + 插件 jar，然后打印真机验收清单。
# 门禁带 -PallowDebugSigning=true：没有 keystore.properties 的机器用 debug 签名跑通（仅侧载冒烟，
# 不能发布）；有 keystore.properties 时该 flag 不改变签名结果。
# 用法：scripts/release-smoke.sh [--install]
#   --install  尝试通过 adb 安装到已连接设备（可选，失败不影响构建结论）
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=${GRADLE:-/opt/gradle-9.7.1/bin/gradle}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-arm64}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
JAVA_PATHS=${JAVA_PATHS:-/usr/lib/jvm/java-21-openjdk-arm64,/usr/lib/jvm/java-25-openjdk-arm64}

echo "== 1/3 单测 =="
"$GRADLE" :plugin-api:testDebugUnitTest :ui-kit:testDebugUnitTest \
  --no-configuration-cache --console=plain \
  -Dorg.gradle.java.installations.paths="$JAVA_PATHS"

echo "== 2/3 release 构建 + 门禁 =="
# 显式放行 debug 签名：没有 keystore.properties 时，门禁按 debug key 放行，仅用于侧载冒烟，
# 产物不能发布；有 keystore.properties 时该 flag 不改变签名结果（仍用发布 key 签名，门禁只不再拦截）。
"$GRADLE" :host:releaseGate -PallowDebugSigning=true \
  --no-configuration-cache --console=plain \
  -Dorg.gradle.java.installations.paths="$JAVA_PATHS"

echo "== 3/3 插件 jar =="
# 当前在构建内的插件：settings、eta-ui、agent 层 5 个。
# eta-ui 依赖 ev.buan.plugins.agent.api，两者必须一起构建/安装，
# 单独投放 eta-ui 会因 DEPENDENCY_MISSING 被拒。
"$GRADLE" :plugins:settings:bundlePlugin :plugins:eta-ui:bundlePlugin \
  :plugins:agent-api:bundlePlugin :plugins:agent-model:bundlePlugin \
  :plugins:agent-tools:bundlePlugin :plugins:agent-memory:bundlePlugin \
  :plugins:agent-brain:bundlePlugin \
  --no-configuration-cache --console=plain \
  -Dorg.gradle.java.installations.paths="$JAVA_PATHS"

APK=host/build/outputs/apk/release/host-release.apk
test -f "$APK" || { echo "缺少 $APK"; exit 1; }
echo "APK: $APK ($(du -h "$APK" | cut -f1))"

for jar in settings eta-ui agent-api agent-model agent-tools agent-memory agent-brain; do
  test -f "plugins/$jar/build/dist/$jar.jar" || { echo "缺少 $jar.jar"; exit 1; }
  test -f "plugins/$jar/build/dist/$jar.json" || { echo "缺少 $jar.json"; exit 1; }
done

if [[ "${1:-}" == "--install" ]] && command -v adb >/dev/null 2>&1; then
  echo "== 安装到设备 =="
  adb install -r "$APK" || echo "adb 安装失败，请手动安装"
fi

cat <<'CHECKLIST'

== 真机验收清单 ==
1. 首次启动：七个 jar（设置/Eta UI + agent-api/model/tools/memory/brain）直接安装（autoTrustUnsigned = true：未签名容器首次同步即记录 (id, sha256)，管理页没有「待确认的未签名插件」区）。
   eta-ui 依赖 agent-api：两者必须同时存在，单独放 eta-ui 会因 DEPENDENCY_MISSING 被拒。
   eta-ui 与 agent-tools/agent-memory 声明了 PLUGIN_STORAGE、agent-model 声明 NETWORK+PLUGIN_STORAGE：未授权时保持「已安装、未激活」（卡片显示 PERMISSION_DENIED 原因，不崩溃）；在管理页授予后自动激活，撤销后立即停用并回到同一提示
2. 重启后仍直接加载；七张插件卡片带「未签名」标注，管理页不出现「信任」入口
3. api2 → 拒绝：API 版本不兼容（插件 2，宿主 1）
4. dup   → 拒绝：插件包内出现宿主/系统类定义
5. fake-jvm → 失败：ClassNotFoundException
6. 设置页改动主题 → 整个界面（含插件页）立即重绘
7. 「打开页面」→ 独立 Activity，返回键回到列表
8. 替换 EV/mods 里同名同 id 的 jar（字节不同）并重启 → 新哈希重新入账并覆盖安装（autoTrustUnsigned = true，不再回到待确认）；同步之后、加载之前被换字节仍会被加载期哈希校验拒载（LOAD_HASH_MISMATCH）
9. agent 层安装：EV/mods 放齐 eta-ui + 5 个 agent jar + 同名 json；管理页给 agent-model 授予「网络访问」「插件存储」，给 agent-tools/agent-memory 授予「插件存储」
10. 未配置 agent-model：聊天页发送任意消息 → 显示「未连接模型 / 尚未配置」，不崩溃；在 agent-model 卡片填写 OpenAI 兼容基址、API Key 与模型名并保存 → 再发送收到真实模型回复
11. 降级：停用 agent-brain → 聊天页回到「（未接模型）我收到了：…」占位；停用 agent-model → 回复未就绪；重新启用后无需重开页面即可继续问答
12. 工具轮：要求模型写文件（如「把 hello 写入 notes.txt」）→ 工具被执行、回复基于工具结果、agent-tools 的存储占用变化；停用 agent-tools 后重试 → 回复说明工具未启用，不崩溃（工具结果以 user 消息回填，最多 3 轮、单条截断 8 KB，不是原生 tool_calls 协议）
CHECKLIST
