package com.latenighthack.ktstore.gradle;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.io.File;
import static org.junit.Assert.*;

public class StoreMigrationsPluginTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void registersTasksAndRequiresCompiledVerification() throws Exception {
        File root = temporary.newFolder();
        Files.writeString(new File(root, "settings.gradle").toPath(), "rootProject.name='consumer'\n");
        Files.writeString(new File(root, "build.gradle").toPath(), "plugins { id 'com.latenighthack.ktstore.migrations' }\n");
        String tasks = GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments("tasks", "--all").build().getOutput();
        assertTrue(tasks.contains("generateStoreMigrations"));
        assertTrue(tasks.contains("checkStoreMigrations"));
        assertTrue(tasks.contains("verifyStoreMigrations"));
        String failure = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("verifyGeneratedStoreMigrations", "-x", "checkStoreMigrations").buildAndFail().getOutput();
        assertTrue(failure.contains("verificationClasspath") || failure.contains("mainClass"));
    }
}
