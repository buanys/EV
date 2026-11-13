package ev.buan.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes the public API snapshot of one SDK module to {@code api/<module>.txt}.
 *
 * <p>Declared inputs/outputs keep the task up-to-date when neither the compiled classes nor the
 * snapshots changed, so a repeated run is a no-op.
 */
public abstract class ApiSnapshotTask extends DefaultTask {

    /** Compiled class directories of the module (javap renders each class once). */
    @InputFiles
    public abstract ConfigurableFileCollection getClasses();

    /** Module name, used in the snapshot header and the task description. */
    @Input
    public abstract Property<String> getModuleName();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        String moduleName = getModuleName().get();
        String snapshot = ApiSnapshot.render(moduleName, getClasses().getFiles());
        File output = getOutputFile().get().getAsFile();
        try {
            Files.createDirectories(output.toPath().getParent());
            Files.writeString(output.toPath(), snapshot, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new GradleException("Cannot write API snapshot " + output + ": " + error.getMessage(), error);
        }
        getLogger().lifecycle("API snapshot written: {} ({} lines)", output, snapshot.lines().count());
    }
}
