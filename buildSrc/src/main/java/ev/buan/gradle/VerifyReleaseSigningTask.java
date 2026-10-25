package ev.buan.gradle;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

/**
 * Release gate: a release build must be signed with the repository-external release key.
 *
 * <p>Signed plugins are trusted by the host certificate, so the release key is not a packaging
 * detail - it decides what the device will accept. A missing {@code keystore.properties} must fail
 * loudly instead of silently producing a debug-signed artifact that looks releasable.
 *
 * <p>There is exactly one explicit opt-out, {@code -PallowDebugSigning=true}, for side-loading
 * smoke runs (and CI, which has no secret material). It only lets this gate pass; it never changes
 * what gets signed.
 *
 * <p>The path is a plain {@code @Input} string rather than an {@code @InputFile}: the file is
 * expected to be absent, and {@code @InputFile} would fail with Gradle's own "does not exist"
 * message before the actionable hint below could be printed. The task declares no outputs, so the
 * existence check runs on every build.
 */
public abstract class VerifyReleaseSigningTask extends DefaultTask {

    /** Absolute path of {@code keystore.properties}; the file is git-ignored and normally absent. */
    @Input
    public abstract Property<String> getKeystorePropertiesPath();

    /** Repository root: {@code storeFile} inside the properties file is resolved against it. */
    @Input
    public abstract Property<String> getRepoRootPath();

    /** {@code -PallowDebugSigning=true}: the single, explicit opt-out from this gate. */
    @Input
    public abstract Property<Boolean> getAllowDebugSigning();

    @TaskAction
    public void verify() {
        File keystoreProperties = new File(getKeystorePropertiesPath().get());
        if (keystoreProperties.isFile()) {
            verifyKeystoreProperties(keystoreProperties);
            return;
        }

        if (getAllowDebugSigning().get()) {
            getLogger().warn(
                "release 缺少 {}：-PallowDebugSigning=true 已显式放行 debug 签名，产物只能侧载，不能发布",
                keystoreProperties.getPath());
            return;
        }

        throw new GradleException(
            "缺少 " + keystoreProperties.getPath() + "：release 构建必须用仓库外的发布密钥签名。\n"
                + "\n"
                + "  1) 复制模板并填值：cp keystore.properties.example keystore.properties\n"
                + "     storeFile 相对仓库根目录；storeFile/storePassword/keyAlias/keyPassword 必填\n"
                + "  2) 没有 key 先生成（保存在仓库外，别提交）：\n"
                + "     keytool -genkeypair -v -keystore ~/ev-release.jks -alias ev-release \\\n"
                + "       -keyalg RSA -keysize 4096 -validity 10000 -dname \"CN=EV, O=EV, C=CN\"\n"
                + "  3) 只做本地侧载冒烟、不需要真实发布签名时，显式放行 debug 签名：\n"
                + "     gradlew :host:releaseGate -PallowDebugSigning=true\n"
                + "\n"
                + "详见 docs/release-signing.md");
    }

    /**
     * Validates the file the build is about to sign with. Without this, a half-filled
     * {@code keystore.properties} surfaces as a signing failure inside AGP with no hint about which
     * property is wrong.
     */
    private void verifyKeystoreProperties(File keystoreProperties) {
        Properties properties = new Properties();
        try {
            String text = Files.readString(keystoreProperties.toPath(), StandardCharsets.UTF_8);
            properties.load(new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + keystoreProperties.getPath() + " 失败", e);
        }

        List<String> required = List.of("storeFile", "storePassword", "keyAlias", "keyPassword");
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new GradleException(
                keystoreProperties.getPath() + " 缺少字段：" + missing
                    + "（模板见 keystore.properties.example，storeFile 相对仓库根目录）");
        }

        File storeFile = new File(getRepoRootPath().get(), properties.getProperty("storeFile").trim());
        if (!storeFile.isFile()) {
            throw new GradleException(
                keystoreProperties.getPath() + " 指向的 storeFile 不存在：" + storeFile.getPath());
        }

        getLogger().lifecycle(
            "发布签名校验通过：storeFile={}（alias={}）",
            storeFile.getPath(),
            properties.getProperty("keyAlias").trim());
    }
}
