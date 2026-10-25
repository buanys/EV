package ev.buan.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Release gate: every package listed in {@code gradle/shared-packages.txt} must own at least one
 * class in the release dex.
 *
 * <p>A Maven <em>group</em> written where a Java <em>package</em> belongs ({@code
 * com.squareup.okhttp3} instead of {@code okhttp3}) looks fine everywhere else and silently stops
 * sharing the library with plugins, so an empty package is a build failure, not a warning.
 *
 * <p>Declared as a standalone task class with file inputs so the build stays configuration-cache
 * compatible: no script object is captured and nothing is read at execution time through {@code
 * Project}.
 */
public abstract class VerifySharedPackagesInDexTask extends DefaultTask {

    /** R8 mapping of the release variant ({@code assembleRelease} writes it). */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMappingFile();

    /** {@code gradle/shared-packages.txt}: the single source of truth for the shared package list. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSharedPackagesFile();

    @TaskAction
    public void verify() {
        List<String> packages = ReleaseGateFiles.readPackageList(getSharedPackagesFile().get().getAsFile());
        Set<String> classNames = ReleaseGateFiles.readReleaseClassNames(getMappingFile().get().getAsFile());

        List<String> empty = new ArrayList<>();
        for (String packageName : packages) {
            boolean present = false;
            for (String className : classNames) {
                if (ReleaseGateFiles.isInPackage(className, packageName)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                empty.add(packageName);
            }
        }

        if (!empty.isEmpty()) {
            throw new IllegalStateException("以下共享包在 release dex 中 0 个类，疑似把 Maven group 当成了包名：" + empty);
        }
        getLogger().lifecycle("共享包校验通过：{} 项", packages.size());
    }
}
