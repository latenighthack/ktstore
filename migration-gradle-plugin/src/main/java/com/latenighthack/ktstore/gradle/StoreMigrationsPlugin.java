package com.latenighthack.ktstore.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Tool/spec compilation is supplied explicitly, so generation cannot depend on its own output. */
public class StoreMigrationsPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        StoreMigrationsExtension extension = project.getExtensions().create("storeMigrations", StoreMigrationsExtension.class, project);
        register(project, extension, "generateStoreMigrations", "generate");
        TaskProvider<JavaExec> check = register(project, extension, "checkStoreMigrations", "check");
        TaskProvider<JavaExec> sqlite = register(project, extension, "verifyStoreMigrationsSqlite", "verify");
        TaskProvider<JavaExec> generated = project.getTasks().register("verifyGeneratedStoreMigrations", JavaExec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Compiles and executes the consumer's generated migration verification entry point.");
            task.dependsOn(check);
            task.setClasspath(extension.getVerificationClasspath());
            task.getMainClass().set(extension.getVerificationMainClass());
            task.doFirst(ignored -> {
                if (extension.getVerificationClasspath().isEmpty() || !extension.getVerificationMainClass().isPresent())
                    throw new IllegalStateException("Configure verificationClasspath and verificationMainClass to execute compiled generated migrations");
            });
        });
        project.getTasks().register("verifyStoreMigrations", task -> {
            task.setGroup("verification");
            task.setDescription("Verifies historical fixtures on SQLite and executes compiled generated migrations.");
            task.dependsOn(sqlite, generated);
        });
        project.getTasks().matching(task -> task.getName().equals("check")).configureEach(task -> task.dependsOn(check));
    }
    private TaskProvider<JavaExec> register(Project project, StoreMigrationsExtension extension, String name, String mode) {
        return project.getTasks().register(name, JavaExec.class, task -> {
            task.setGroup(mode.equals("generate") ? "migration tooling" : "verification");
            task.setDescription(mode + " reviewed ktstore migration artifacts");
            task.getMainClass().set("com.latenighthack.ktstore.tooling.MigrationTool");
            task.setClasspath(extension.getToolClasspath());
            // Always compare artifacts and locks, including manually edited output.
            task.getOutputs().upToDateWhen(ignored -> false);
            task.doFirst(ignored -> {
                List<String> arguments = new ArrayList<>();
                arguments.add(mode);
                arguments.add(extension.getProviderClass().get());
                arguments.add(extension.getPackageName().get());
                arguments.add(extension.getSpecificationReference().get());
                arguments.add(extension.getOutputDirectory().get().getAsFile().getAbsolutePath());
                arguments.add(extension.getHistoricalRoot().get().getAsFile().getAbsolutePath());
                extension.getHistoricalInputs().getFiles().stream().sorted().map(File::getAbsolutePath).forEach(arguments::add);
                task.setArgs(arguments);
            });
        });
    }
}
