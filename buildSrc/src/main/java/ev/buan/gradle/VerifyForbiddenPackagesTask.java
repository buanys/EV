package ev.buan.gradle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Release gate: the release dex must not contain packages the platform already provides.
 *
 * <p>Re-bundling {@code org.json} (or the other {@code org.apache.http} / {@code org.xml*}
 * lookalikes) shadows core-libart; R8 then inlines references to fields the platform class does not
 * have, which only shows up as a crash on a device. {@code material.icons.extended} is listed for a
 * different reason: the host ships icons-core only, so bundling icons-extended hands plugins a
 * package they can never resolve at runtime.
 *
 * <p>Standalone task class (no captured script object, declared inputs) so the build stays
 * configuration-cache compatible.
 */
public abstract class VerifyForbiddenPackagesTask extends DefaultTask {

    /** R8 mapping of the release variant ({@code assembleRelease} writes it). */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMappingFile();

    /** Packages that must not appear in the release dex, configured by the host build script. */
    @Input
    public abstract ListProperty<String> getForbiddenPackages();

    @TaskAction
    public void verify() {
        Set<String> classNames = ReleaseGateFiles.readReleaseClassNames(getMappingFile().get().getAsFile());
        List<String> packages = getForbiddenPackages().get();

        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (String packageName : packages) {
            List<String> matched = classNames.stream()
                .filter(className -> ReleaseGateFiles.isInPackage(className, packageName))
                .sorted()
                .toList();
            if (!matched.isEmpty()) {
                hits.put(packageName, matched.size());
                examples.put(packageName, matched.subList(0, Math.min(3, matched.size())));
            }
        }

        if (!hits.isEmpty()) {
            StringBuilder message = new StringBuilder("release dex 混入了禁忌包：");
            hits.forEach((packageName, count) -> message
                .append('\n')
                .append("  ")
                .append(packageName)
                .append(" -> ")
                .append(count)
                .append(" 个类，例如 ")
                .append(examples.get(packageName)));
            throw new IllegalStateException(message.toString());
        }
        getLogger().lifecycle("禁忌包校验通过：{} 项均未出现", packages.size());
    }
}
