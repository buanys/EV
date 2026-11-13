package ev.buan.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * File parsing helpers shared by the release-gate tasks in this package.
 *
 * <p>Everything here is static and file-based on purpose: task actions must not reach back into the
 * build script or the {@code Project} object, or the configuration cache cannot serialize the task.
 */
final class ReleaseGateFiles {

    private static final String CLASS_MAPPING_SEPARATOR = " -> ";

    private ReleaseGateFiles() {
    }

    /**
     * Reads the class list out of an R8 mapping file ({@code outputs/mapping/<variant>/mapping.txt}).
     *
     * <p>Only top-level {@code original -> obfuscated} lines are class mappings; member mappings are
     * indented, so they are skipped. The <em>original</em> (unobfuscated) name is what the package
     * checks match against: obfuscated names would hide the packages we are looking for.
     */
    static Set<String> readReleaseClassNames(File mappingFile) {
        if (!mappingFile.isFile()) {
            throw new IllegalStateException("缺少 " + mappingFile.getPath() + "，先跑 :host:assembleRelease");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : readLines(mappingFile)) {
            if (line.isEmpty() || line.startsWith(" ") || !line.contains(CLASS_MAPPING_SEPARATOR)) {
                continue;
            }
            names.add(line.substring(0, line.indexOf(CLASS_MAPPING_SEPARATOR)).trim());
        }
        return names;
    }

    /** True when {@code className} is exactly {@code packageName} or lives inside it. */
    static boolean isInPackage(String className, String packageName) {
        return className.equals(packageName) || className.startsWith(packageName + ".");
    }

    /**
     * Reads a package list file: one Java package per line, {@code #} starts a comment.
     *
     * <p>The file is a plain list (not a script) so it stays readable in a diff and can be declared
     * as a task input.
     */
    static List<String> readPackageList(File file) {
        List<String> packages = new ArrayList<>();
        for (String line : readLines(file)) {
            int comment = line.indexOf('#');
            String value = (comment >= 0 ? line.substring(0, comment) : line).trim();
            if (!value.isEmpty()) {
                packages.add(value);
            }
        }
        return packages;
    }

    private static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + file.getPath() + " 失败", e);
        }
    }
}
