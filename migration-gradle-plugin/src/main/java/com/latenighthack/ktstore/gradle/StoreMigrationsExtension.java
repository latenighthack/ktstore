package com.latenighthack.ktstore.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

public class StoreMigrationsExtension {
    private final Property<String> providerClass;
    private final Property<String> specificationReference;
    private final Property<String> packageName;
    private final DirectoryProperty outputDirectory;
    private final DirectoryProperty historicalRoot;
    private final ConfigurableFileCollection toolClasspath;
    private final ConfigurableFileCollection historicalInputs;
    private final ConfigurableFileCollection verificationClasspath;
    private final Property<String> verificationMainClass;
    public StoreMigrationsExtension(Project project) {
        providerClass = project.getObjects().property(String.class);
        specificationReference = project.getObjects().property(String.class);
        packageName = project.getObjects().property(String.class);
        outputDirectory = project.getObjects().directoryProperty();
        outputDirectory.convention(project.getLayout().getProjectDirectory().dir("src/migrations"));
        historicalRoot = project.getObjects().directoryProperty();
        historicalRoot.convention(project.getLayout().getProjectDirectory());
        toolClasspath = project.files();
        historicalInputs = project.files();
        verificationClasspath = project.files();
        verificationMainClass = project.getObjects().property(String.class);
    }
    public Property<String> getProviderClass() { return providerClass; }
    public Property<String> getSpecificationReference() { return specificationReference; }
    public Property<String> getPackageName() { return packageName; }
    public DirectoryProperty getHistoricalRoot() { return historicalRoot; }
    public DirectoryProperty getOutputDirectory() { return outputDirectory; }
    public ConfigurableFileCollection getToolClasspath() { return toolClasspath; }
    public ConfigurableFileCollection getHistoricalInputs() { return historicalInputs; }
    public ConfigurableFileCollection getVerificationClasspath() { return verificationClasspath; }
    public Property<String> getVerificationMainClass() { return verificationMainClass; }
}
