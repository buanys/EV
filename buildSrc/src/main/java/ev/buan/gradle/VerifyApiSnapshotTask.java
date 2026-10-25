package ev.buan.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails when the public API of one SDK module differs from its committed baseline.
 *
 * <p>This is the CI gate: it renders the same text as {@link ApiSnapshotTask} in memory and
 * compares it with {@code api/<module>.txt}. The message shows the first differing lines and the
 * command that refreshes the baseline, so a contributor can tell an intended API change from an
 * accidental one.
 */
public abstract class VerifyApiSnapshotTask extends DefaultTask {

    @InputFiles
    public abstract ConfigurableFileCollection getClasses();

    @Input
    public abstract Property<String> getModuleName();

    @InputFile
    public abstract RegularFileProperty getExpectedFile();

    @TaskAction
    public void verify() {
        String moduleName = getModuleName().get();
        File expectedFile = getExpectedFile().get().getAsFile();
        if (!expectedFile.isFile()) {
            throw new GradleException(
                    "Missing SDK API baseline " + expectedFile
                            + ". Run ./gradlew :" + moduleName + ":apiSnapshot and commit api/" + moduleName + ".txt.");
        }
        String actual;
        try {
            actual = ApiSnapshot.render(moduleName, getClasses().getFiles());
        } catch (RuntimeException error) {
            throw new GradleException("Cannot render the API snapshot for :" + moduleName + ": " + error.getMessage(), error);
        }
        String expected;
        try {
            expected = Files.readString(expectedFile.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException error) {
            throw new GradleException("Cannot read " + expectedFile + ": " + error.getMessage(), error);
        }
        if (actual.equals(expected)) {
            getLogger().lifecycle("SDK API snapshot OK: :{} ({} lines)", moduleName, actual.lines().count());
            return;
        }
        throw new GradleException(diffMessage(moduleName, expectedFile, expected, actual));
    }

    private static String diffMessage(String moduleName, File expectedFile, String expected, String actual) {
        List<String> expectedLines = List.of(expected.split("\n", -1));
        List<String> actualLines = List.of(actual.split("\n", -1));
        List<String> differences = new ArrayList<>();
        int limit = Math.max(expectedLines.size(), actualLines.size());
        for (int index = 0; index < limit && differences.size() < 40; index++) {
            String left = index < expectedLines.size() ? expectedLines.get(index) : "<missing>";
            String right = index < actualLines.size() ? actualLines.get(index) : "<missing>";
            if (!left.equals(right)) {
                differences.add("  line " + (index + 1) + "\n    baseline: " + left + "\n    current : " + right);
            }
        }
        return "SDK public API changed for :" + moduleName + ".\n"
                + "Baseline: " + expectedFile + " (" + expectedLines.size() + " lines), "
                + "current: " + actualLines.size() + " lines.\n"
                + String.join("\n", differences)
                + "\nIf the change is intended, run ./gradlew :" + moduleName + ":apiSnapshot"
                + " and commit api/" + moduleName + ".txt together with the code change.";
    }
}
