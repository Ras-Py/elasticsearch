/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.test.rest;

import groovy.lang.Closure;

import org.elasticsearch.gradle.Architecture;
import org.elasticsearch.gradle.DistributionDownloadPlugin;
import org.elasticsearch.gradle.ElasticsearchDistribution;
import org.elasticsearch.gradle.VersionProperties;
import org.elasticsearch.gradle.distribution.ElasticsearchDistributionTypes;
import org.elasticsearch.gradle.internal.ElasticsearchJavaPlugin;
import org.elasticsearch.gradle.internal.InternalDistributionDownloadPlugin;
import org.elasticsearch.gradle.internal.info.BuildParams;
import org.elasticsearch.gradle.plugin.BasePluginBuildPlugin;
import org.elasticsearch.gradle.plugin.PluginBuildPlugin;
import org.elasticsearch.gradle.plugin.PluginPropertiesExtension;
import org.elasticsearch.gradle.test.SystemPropertyCommandLineArgumentProvider;
import org.elasticsearch.gradle.testclusters.StandaloneRestIntegTestTask;
import org.elasticsearch.gradle.transform.UnzipTransform;
import org.elasticsearch.gradle.util.GradleUtils;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.util.PatternFilterable;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Base plugin used for wiring up build tasks to REST testing tasks using new JUnit rule-based test clusters framework.
 */
public class RestTestBasePlugin implements Plugin<Project> {

    private static final String TESTS_RUNTIME_JAVA_SYSPROP = "tests.runtime.java";
    private static final String DEFAULT_DISTRIBUTION_SYSPROP = "tests.default.distribution";
    private static final String INTEG_TEST_DISTRIBUTION_SYSPROP = "tests.integ-test.distribution";
    private static final String TESTS_CLUSTER_MODULES_PATH_SYSPROP = "tests.cluster.modules.path";
    private static final String TESTS_CLUSTER_PLUGINS_PATH_SYSPROP = "tests.cluster.plugins.path";
    private static final String DEFAULT_REST_INTEG_TEST_DISTRO = "default_distro";
    private static final String INTEG_TEST_REST_INTEG_TEST_DISTRO = "integ_test_distro";
    private static final String MODULES_CONFIGURATION = "clusterModules";
    private static final String PLUGINS_CONFIGURATION = "clusterPlugins";
    private static final String EXTRACTED_PLUGINS_CONFIGURATION = "extractedPlugins";

    private final ProviderFactory providerFactory;

    @Inject
    public RestTestBasePlugin(ProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(ElasticsearchJavaPlugin.class);
        project.getPluginManager().apply(InternalDistributionDownloadPlugin.class);

        // Register integ-test and default distributions
        NamedDomainObjectContainer<ElasticsearchDistribution> distributions = DistributionDownloadPlugin.getContainer(project);
        ElasticsearchDistribution defaultDistro = distributions.create(DEFAULT_REST_INTEG_TEST_DISTRO, distro -> {
            distro.setVersion(VersionProperties.getElasticsearch());
            distro.setArchitecture(Architecture.current());
        });
        ElasticsearchDistribution integTestDistro = distributions.create(INTEG_TEST_REST_INTEG_TEST_DISTRO, distro -> {
            distro.setVersion(VersionProperties.getElasticsearch());
            distro.setArchitecture(Architecture.current());
            distro.setType(ElasticsearchDistributionTypes.INTEG_TEST_ZIP);
        });

        // Create configures for module and plugin dependencies
        Configuration modulesConfiguration = createPluginConfiguration(project, MODULES_CONFIGURATION, true, false);
        Configuration pluginsConfiguration = createPluginConfiguration(project, PLUGINS_CONFIGURATION, false, false);
        Configuration extractedPluginsConfiguration = createPluginConfiguration(project, EXTRACTED_PLUGINS_CONFIGURATION, true, true);
        extractedPluginsConfiguration.extendsFrom(pluginsConfiguration);
        configureArtifactTransforms(project);

        // For plugin and module projects, register the current project plugin bundle as a dependency
        project.getPluginManager().withPlugin("elasticsearch.esplugin", plugin -> {
            if (GradleUtils.isModuleProject(project.getPath())) {
                project.getDependencies().add(MODULES_CONFIGURATION, getExplodedBundleDependency(project, project.getPath()));
            } else {
                project.getDependencies().add(PLUGINS_CONFIGURATION, getBundleZipTaskDependency(project, project.getPath()));
            }

        });

        project.getTasks().withType(StandaloneRestIntegTestTask.class, task -> {
            SystemPropertyCommandLineArgumentProvider nonInputSystemProperties = task.getExtensions()
                .getByType(SystemPropertyCommandLineArgumentProvider.class);

            task.dependsOn(integTestDistro, modulesConfiguration);
            registerDistributionInputs(task, integTestDistro);

            // Enable parallel execution for these tests since each test gets its own cluster
            task.setMaxParallelForks(task.getProject().getGradle().getStartParameter().getMaxWorkerCount() / 2);

            // Disable test failure reporting since this stuff is now captured in build scans
            task.getExtensions().getExtraProperties().set("dumpOutputOnFailure", false);

            // Disable the security manager and syscall filter since the test framework needs to fork processes
            task.systemProperty("tests.security.manager", "false");
            task.systemProperty("tests.system_call_filter", "false");

            // Register plugins and modules as task inputs and pass paths as system properties to tests
            nonInputSystemProperties.systemProperty(TESTS_CLUSTER_MODULES_PATH_SYSPROP, modulesConfiguration::getAsPath);
            registerConfigurationInputs(task, modulesConfiguration);
            nonInputSystemProperties.systemProperty(TESTS_CLUSTER_PLUGINS_PATH_SYSPROP, pluginsConfiguration::getAsPath);
            registerConfigurationInputs(task, extractedPluginsConfiguration);

            // Wire up integ-test distribution by default for all test tasks
            nonInputSystemProperties.systemProperty(
                INTEG_TEST_DISTRIBUTION_SYSPROP,
                () -> integTestDistro.getExtracted().getSingleFile().getPath()
            );
            nonInputSystemProperties.systemProperty(TESTS_RUNTIME_JAVA_SYSPROP, BuildParams.getRuntimeJavaHome());

            // Add `usesDefaultDistribution()` extension method to test tasks to indicate they require the default distro
            task.getExtensions().getExtraProperties().set("usesDefaultDistribution", new Closure<Void>(task) {
                @Override
                public Void call(Object... args) {
                    task.dependsOn(defaultDistro);
                    registerDistributionInputs(task, defaultDistro);

                    nonInputSystemProperties.systemProperty(
                        DEFAULT_DISTRIBUTION_SYSPROP,
                        providerFactory.provider(() -> defaultDistro.getExtracted().getSingleFile().getPath())
                    );
                    return null;
                }
            });
        });

        project.getTasks()
            .named(JavaBasePlugin.CHECK_TASK_NAME)
            .configure(check -> check.dependsOn(project.getTasks().withType(StandaloneRestIntegTestTask.class)));
    }

    private FileTree getDistributionFiles(ElasticsearchDistribution distribution, Action<PatternFilterable> patternFilter) {
        return distribution.getExtracted().getAsFileTree().matching(patternFilter);
    }

    private void registerConfigurationInputs(Task task, Configuration configuration) {
        task.getInputs()
            .files(providerFactory.provider(() -> configuration.getAsFileTree().filter(f -> f.getName().endsWith(".jar") == false)))
            .withPropertyName(configuration.getName() + "-files")
            .withPathSensitivity(PathSensitivity.RELATIVE);

        task.getInputs()
            .files(providerFactory.provider(() -> configuration.getAsFileTree().filter(f -> f.getName().endsWith(".jar"))))
            .withPropertyName(configuration.getName() + "-classpath")
            .withNormalizer(ClasspathNormalizer.class);
    }

    private void registerDistributionInputs(Task task, ElasticsearchDistribution distribution) {
        task.getInputs()
            .files(providerFactory.provider(() -> getDistributionFiles(distribution, filter -> filter.exclude("**/*.jar"))))
            .withPropertyName(distribution.getName() + "-files")
            .withPathSensitivity(PathSensitivity.RELATIVE);

        task.getInputs()
            .files(providerFactory.provider(() -> getDistributionFiles(distribution, filter -> filter.include("**/*.jar"))))
            .withPropertyName(distribution.getName() + "-classpath")
            .withNormalizer(ClasspathNormalizer.class);
    }

    private Optional<String> findModulePath(Project project, String pluginName) {
        return project.getRootProject()
            .getAllprojects()
            .stream()
            .filter(p -> GradleUtils.isModuleProject(p.getPath()))
            .filter(p -> p.getPlugins().hasPlugin(PluginBuildPlugin.class))
            .filter(p -> p.getExtensions().getByType(PluginPropertiesExtension.class).getName().equals(pluginName))
            .findFirst()
            .map(Project::getPath);
    }

    private Configuration createPluginConfiguration(Project project, String name, boolean useExploded, boolean isExtended) {
        return project.getConfigurations().create(name, c -> {
            if (useExploded) {
                c.attributes(a -> a.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE));
            } else {
                c.attributes(a -> a.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE));
            }
            if (isExtended == false) {
                c.withDependencies(dependencies -> {
                    // Add dependencies of any modules
                    Collection<Dependency> additionalDependencies = new LinkedHashSet<>();
                    for (Iterator<Dependency> iterator = dependencies.iterator(); iterator.hasNext();) {
                        Dependency dependency = iterator.next();
                        if (dependency instanceof ProjectDependency projectDependency) {
                            Project dependencyProject = projectDependency.getDependencyProject();
                            List<String> extendedPlugins = dependencyProject.getExtensions()
                                .getByType(PluginPropertiesExtension.class)
                                .getExtendedPlugins();

                            // Replace project dependency with explicit dependency on exploded configuration to workaround variant bug
                            if (projectDependency.getTargetConfiguration() == null) {
                                iterator.remove();
                                additionalDependencies.add(
                                    useExploded
                                        ? getExplodedBundleDependency(project, dependencyProject.getPath())
                                        : getBundleZipTaskDependency(project, dependencyProject.getPath())
                                );
                            }

                            for (String extendedPlugin : extendedPlugins) {
                                findModulePath(project, extendedPlugin).ifPresent(
                                    modulePath -> additionalDependencies.add(
                                        useExploded
                                            ? getExplodedBundleDependency(project, modulePath)
                                            : getBundleZipTaskDependency(project, modulePath)
                                    )
                                );
                            }
                        }
                    }

                    dependencies.addAll(additionalDependencies);
                });
            }
        });
    }

    private Dependency getExplodedBundleDependency(Project project, String projectPath) {
        return project.getDependencies()
            .project(Map.of("path", projectPath, "configuration", BasePluginBuildPlugin.EXPLODED_BUNDLE_CONFIG));
    }

    private Dependency getBundleZipTaskDependency(Project project, String projectPath) {
        Project dependencyProject = project.findProject(projectPath);
        return project.getDependencies()
            .create(project.files(dependencyProject.getTasks().named(BasePluginBuildPlugin.BUNDLE_PLUGIN_TASK_NAME)));
    }

    private void configureArtifactTransforms(Project project) {
        project.getDependencies().registerTransform(UnzipTransform.class, transformSpec -> {
            transformSpec.getFrom().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.ZIP_TYPE);
            transformSpec.getTo().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.DIRECTORY_TYPE);
            transformSpec.getParameters().setAsFiletreeOutput(false);
        });
    }
}
