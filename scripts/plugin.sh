#!/bin/sh
#
# EV 插件开发助手。两个子命令分别跑在两个环境里，因为它们需要的东西不一样：
#
#   build   需要 JDK / Android SDK / Gradle —— 在 Linux 环境（chroot）里跑
#   deploy  需要 am / logcat / root      —— 在 Android 环境里跑
#
# 为什么不做成"一条命令"：这两边各自有独立的文件系统视图和命名空间，
# 硬串起来只会在某台机器上坏掉，而且坏得很难查。分开之后每一步都能单独重跑。
#
set -u

usage() {
    cat <<'EOF'
用法：
  plugin.sh build <module>
      在 Linux 环境里编译并打包，产物在 plugins/<module>/build/dist/

  plugin.sh deploy <module> [--no-launch] [--display N]
      在 Android(root) 环境里：复制到 EV/mods、更新 sha256 信任、重启、抓加载日志

<module> 是 plugins/ 下的目录名，例如 agent-log、my-plugin。
EOF
}

die() { echo "错误：$*" >&2; exit 1; }

ev_root() {
    if [ -d /data/local/tmp/eta/EV/plugins ]; then
        echo /data/local/tmp/eta/EV
    elif [ -d /workspace/EV/plugins ]; then
        echo /workspace/EV
    else
        return 1
    fi
}

# ---------------------------------------------------------------- build

cmd_build() {
    module=${1:-}
    [ -n "$module" ] || { usage; exit 2; }

    root=$(ev_root) || die "找不到 EV 仓库（既没有 /data/local/tmp/eta/EV 也没有 /workspace/EV）"
    gradle_bin=${GRADLE_BIN:-/opt/gradle-9.7.1/bin/gradle}
    [ -x "$gradle_bin" ] || die "找不到 Gradle：$gradle_bin（可用 GRADLE_BIN 覆盖）"

    echo "==> 构建 :plugins:$module"
    (
        cd "$root" || exit 1
        JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64} \
        ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk} \
        "$gradle_bin" ":plugins:$module:bundlePlugin" --console=plain
    ) || die "构建失败"

    dist="$root/plugins/$module/build/dist"
    [ -d "$dist" ] || die "没有产物目录：$dist"
    echo "==> 产物"
    ls -l "$dist" || exit 1
    echo "==> sha256"
    sha256sum "$dist"/*.jar || exit 1
}

# ---------------------------------------------------------------- deploy

# sidecar 里的 id，例如 ev.buan.plugins.agent.log
sidecar_id() {
    sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -n 1
}

# 把一个 id -> sha256 写进 EV 的未签名信任表。
# 必须用同样的 sha256 语义：EV 信任的是字节，jar 改一个字节就要重新确认。
trust() {
    xml=$1
    id=$2
    sha=$3
    uid=$4

    mkdir -p "$(dirname "$xml")" || return 1
    if [ ! -f "$xml" ]; then
        printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n" > "$xml" || return 1
    fi
    # 先删掉同 id 的旧条目，再在 </map> 前插一条。条目很少，这样比维护一个解析器稳。
    sed -i "/name=\"$id\"/d" "$xml" || return 1
    sed -i "s#</map>#    <string name=\"$id\">$sha</string>\n</map>#" "$xml" || return 1
    chown "$uid:$uid" "$xml" 2>/dev/null || true
    chmod 660 "$xml" 2>/dev/null || true
}

cmd_deploy() {
    module=${1:-}
    [ -n "$module" ] || { usage; exit 2; }
    shift

    launch=1
    display=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --no-launch) launch=0 ;;
            --display) shift; display=${1:-} ;;
            *) die "不认识的参数：$1" ;;
        esac
        shift
    done

    command -v am >/dev/null 2>&1 || die "deploy 必须在 Android 环境里跑（找不到 am）"
    [ "$(id -u)" = "0" ] || die "deploy 需要 root"

    root=$(ev_root) || die "找不到 EV 仓库"
    dist="$root/plugins/$module/build/dist"

    jar=$(ls "$dist"/*.jar 2>/dev/null | head -n 1)
    json=$(ls "$dist"/*.json 2>/dev/null | head -n 1)
    [ -n "$jar" ] || die "没有 jar。先跑：plugin.sh build $module"
    [ -n "$json" ] || die "没有 sidecar json。先跑：plugin.sh build $module"

    id=$(sidecar_id "$json")
    [ -n "$id" ] || die "sidecar 里没有 id：$json"

    mods=/sdcard/EV/mods
    [ -d "$mods" ] || die "没有 $mods"

    # uid 不能靠 stat /data/data/ev.buan：Eta 的 shell 与 EV 在各自的 mount namespace 里，
    # 那边的属主可能是 root，而 EV 自己的 uid 由 PackageManager 说了算。
    app_uid=$(pm list packages -U 2>/dev/null \
        | sed -n 's/^package:ev\.buan uid:\([0-9][0-9]*\).*/\1/p' | head -n 1)
    [ -n "$app_uid" ] || die "查不到 ev.buan 的 uid"

    # /sdcard 的属主跟着写它的进程走，覆盖已有插件时沿用同名的那个，比按目录猜稳。
    ref="$mods/$(basename "$jar")"
    if [ -f "$ref" ]; then
        mods_owner=$(stat -c '%u:%g' "$ref")
    else
        mods_owner=$(stat -c '%u:%g' "$mods")
    fi

    echo "==> 插件 $id"
    cp -f "$jar" "$mods/$(basename "$jar")" || die "复制 jar 失败"
    cp -f "$json" "$mods/$(basename "$json")" || die "复制 json 失败"
    chown "$mods_owner" "$mods/$(basename "$jar")" "$mods/$(basename "$json")" 2>/dev/null || true
    chmod 660 "$mods/$(basename "$jar")" "$mods/$(basename "$json")" 2>/dev/null || true

    sha=$(sha256sum "$mods/$(basename "$jar")" | awk '{print $1}')
    echo "    sha256 $sha"

    # 先停再写：SharedPreferences 在进程里有一份缓存，运行时改文件会被它覆盖回去。
    echo "==> 停止 EV"
    am force-stop ev.buan
    sleep 1

    echo "==> 更新信任"
    trust /data/data/ev.buan/shared_prefs/ev.unsigned.xml "$id" "$sha" "$app_uid" \
        || die "写信任表失败"

    # 这台设备上 EV 跑在 Eta 的沙箱里，它的 /data/data 是一层 tmpfs 覆盖：
    # 同一个路径在我们这里是真实磁盘（inode 21），在 EV 那里是另一个文件（inode 614986）。
    # 也就是说上面这次写入 EV 根本看不到——新插件仍然会停在「待确认」。
    # 保留写入是因为换一台没有沙箱的设备它是有用的；但别把它当成保证。
    sandboxed=$(nsenter -t "$(pidof ev.buan | awk '{print $1}')" -m -- stat -c %i /data/data/ev.buan 2>/dev/null || true)
    ours=$(stat -c %i /data/data/ev.buan 2>/dev/null || true)
    if [ -n "$sandboxed" ] && [ "$sandboxed" != "$ours" ]; then
        echo "    注意：EV 的 /data/data 是沙箱覆盖层，本次写入它看不到"
        echo "    新插件请启动后在插件页点一次「信任」"
    fi

    if [ "$launch" = "0" ]; then
        echo "==> 已跳过启动（--no-launch）"
        return 0
    fi

    echo "==> 启动 EV"
    logcat -c 2>/dev/null || true
    if [ -n "$display" ]; then
        am start --display "$display" -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER ev.buan >/dev/null 2>&1 \
            || die "在 display $display 上启动失败"
    else
        monkey -p ev.buan -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
            || die "启动失败"
    fi

    sleep 9
    pid=$(pidof ev.buan | awk '{print $1}')
    [ -n "$pid" ] || die "EV 没有起来"

    echo "==> 加载结果"
    logcat -d --pid="$pid" 2>/dev/null \
        | grep -E "plugin (accepted|rejected|pending) $id" \
        || echo "    （日志里没有这个插件的状态，可能还没扫描到）"

    broken=$(logcat -d --pid="$pid" 2>/dev/null | grep -c "DEPENDENCY_MISSING")
    if [ "$broken" -gt 0 ]; then
        echo "==> 警告：有 $broken 个插件因依赖缺失被拒"
        logcat -d --pid="$pid" 2>/dev/null | grep "DEPENDENCY_MISSING" | head -n 5
    fi
}

# ---------------------------------------------------------------- main

case "${1:-}" in
    build) shift; cmd_build "$@" ;;
    deploy) shift; cmd_deploy "$@" ;;
    -h|--help|help|"") usage; exit 0 ;;
    *) usage; exit 2 ;;
esac
