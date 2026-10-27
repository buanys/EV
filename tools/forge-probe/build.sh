#!/usr/bin/env bash
# forge-probe 打包脚本（参考用，路径按需调整）
#
# 依赖：JDK 21、android-37 android.jar、build-tools 37.0.0 的 d8.jar
# 产物：/tmp/forge-probe.jar → 拷到设备 /sdcard/EV/mods/compileprobe.jar
#
# !!! 关键：kotlin-stdlib 只作 --lib 参考，绝不能打进 dex（会被 EV 拒） !!!

set -euo pipefail

C=$HOME/.gradle/caches/modules-2/files-2.1
KC=$(ls $C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20/*/*.jar)
STD=$(ls $C/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/*.jar)
CO=$(ls $C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/*/*.jar | head -1)
ANN=$(ls $C/org.jetbrains/annotations/*/*/*.jar | tail -1)
ANDROID=/opt/android-sdk/platforms/android-37.0/android.jar
D8JAR=/opt/android-sdk/build-tools/37.0.0/lib/d8.jar
JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
WORK=${WORK:-/tmp/forge}

# 1. 编译探针自身（classpath 要包含编译器 jar 和 d8.jar，因为它直接引用它们）
rm -rf $WORK/classes && mkdir -p $WORK/classes
$JAVA -cp "$KC:$STD:$CO:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 \
  -classpath "$ANDROID:$WORK/compiledeps/core.jar:$WORK/compiledeps/plugin-api.jar:$STD:$ANN:$KC:$D8JAR" \
  -d $WORK/classes $WORK/src/CompileProbe.kt

# 2. 探针 + patch 类 → classes.dex（放最前，覆盖后面的原版）
rm -rf $WORK/pdex && mkdir -p $WORK/pdex
$JAVA -Xmx1g -cp "$D8JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID" --lib "$STD" --min-api 33 --output $WORK/pdex \
  $(find $WORK/classes -name "*.class") $(find $WORK/patchcls -name "*.class")

# 3. 按顺序拼 10 个 dex
cd $WORK && rm -f final_classes*.dex
cp pdex/classes.dex final_classes.dex
i=2; for f in cdex/classes*.dex;  do cp "$f" "final_classes$i.dex"; i=$((i+1)); done
cp tdex/classes.dex  final_classes7.dex
i=8; for f in ddex/classes*.dex;  do cp "$f" "final_classes$i.dex"; i=$((i+1)); done
i=10; for f in anndex/classes*.dex; do cp "$f" "final_classes$i.dex"; i=$((i+1)); done

# 4. 打包 + 资源
ANN_PATH="$ANN" STD_PATH="$STD" python3 - <<'PY'
import zipfile, glob, os
os.chdir(os.environ.get('WORK', '/tmp/forge'))
files = sorted(glob.glob('final_classes*.dex'),
               key=lambda p: int(''.join(c for c in os.path.basename(p) if c.isdigit()) or 1))
z = zipfile.ZipFile('/tmp/forge-probe.jar', 'w', zipfile.ZIP_DEFLATED)
for f in files:
    z.write(f, f.replace('final_', ''))
z.write('compiledeps/core.jar', 'core.jar')
z.write('compiledeps/plugin-api.jar', 'plugin-api.jar')
z.write(os.environ['STD_PATH'], 'kotlin-stdlib.jar')
z.write('android-stub.jar', 'android-stub.jar')
z.write(os.environ['ANN_PATH'], 'annotations.jar')
z.write('meta/META-INF/compiler.version', 'META-INF/compiler.version')
for s in glob.glob('svc/META-INF/services/*'):
    if os.path.isfile(s):
        z.write(s, 'META-INF/services/' + os.path.basename(s))
z.close()
print('ok:', len(files), 'dex')
PY
echo "产物: /tmp/forge-probe.jar"
