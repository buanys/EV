package ev.buan.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

/**
 * 发布版本一致性门禁：**tag 必须等于 {@code v + versionName}**。
 *
 * <p>为什么需要它：tag 决定 Release 的名字和资产文件名（{@code EV-v0.2.0.apk}），而设备上真正
 * 显示的版本来自 {@code host-app} 的 {@code versionName}。两者是两份独立的手写事实——2026-11-07
 * 的自测就出现过 tag 写 {@code v0.1.1-test}、APK 里还是 {@code 0.1.0} 的情况：CI 全绿、包也发出来了，
 * 装到手机上却显示旧版本号。发布之后才发现，只能重发。
 *
 * <p>三处刻意设计：
 *
 * <ul>
 *   <li><b>没有 tag 时只警告不失败</b>：本地 {@code :host-app:releaseGate} 与侧载冒烟没有 tag 可对，
 *       让它们因为"没提供 tag"而红掉，等于逼人绕过门禁。
 *   <li><b>tag 允许带 {@code refs/tags/} 前缀</b>：GitHub Actions 的 {@code github.ref_name} 不带，
 *       但本地手工跑 {@code git describe} 时会带——两种都接。
 *   <li><b>版本号必须是三段数字</b>：上游工具与商店都按这个形状解析；写成 {@code 0.1} 或
 *       {@code 0.1.0-beta} 会让 tag 与 versionName 的比较失去意义。
 * </ul>
 */
public abstract class VerifyReleaseVersionTask extends DefaultTask {

    /**
     * 期望的发布 tag，例如 {@code v0.1.0}；空字符串表示"不是一次发布构建"，只警告。
     */
    @Input
    @Optional
    public abstract Property<String> getExpectedTag();

    /** 应用版本名，来自 {@code host-app} 的 {@code defaultConfig.versionName}。 */
    @Input
    public abstract Property<String> getVersionName();

    /** 应用版本号；每次发版必须递增，否则设备拒绝覆盖安装。 */
    @Input
    public abstract Property<Integer> getVersionCode();

    @TaskAction
    public void verify() {
        String versionName = getVersionName().get().trim();
        int versionCode = getVersionCode().get();
        String rawTag = getExpectedTag().getOrElse("").trim();

        if (!versionName.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new GradleException(
                "versionName 必须是三段数字（例如 0.2.0），当前是 \"" + versionName
                    + "\"：tag 与它的比较依赖这个形状。改 host-app/build.gradle.kts。");
        }
        if (versionCode < 1) {
            throw new GradleException("versionCode 必须 ≥ 1，当前是 " + versionCode + "。");
        }

        if (rawTag.isEmpty()) {
            getLogger().warn(
                "verifyReleaseVersion: 没有提供 EV_RELEASE_TAG / -PevReleaseTag，跳过一致性校验"
                    + "（当前 versionName={}，versionCode={}）。发布构建请显式传入 tag。",
                versionName, versionCode);
            return;
        }

        String tag = rawTag;
        if (tag.startsWith("refs/tags/")) {
            tag = tag.substring("refs/tags/".length());
        }
        String expected = "v" + versionName;
        if (!tag.equals(expected)) {
            throw new GradleException(
                "发布 tag 与 versionName 不一致：tag=\"" + tag + "\"，期望 \"" + expected + "\""
                    + "（versionCode=" + versionCode + "）。\n"
                    + "二选一：把 host-app/build.gradle.kts 的 versionName 改成 " + tag.replaceFirst("^v", "")
                    + "（同时 versionCode +1），或者换一个与 versionName 匹配的 tag。");
        }

        getLogger().lifecycle(
            "verifyReleaseVersion: OK —— tag {} 与 versionName {} / versionCode {} 一致。",
            tag, versionName, versionCode);
    }
}
