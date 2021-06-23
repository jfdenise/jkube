/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.wildfly.jar.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import org.eclipse.jkube.generator.api.GeneratorContext;
import org.eclipse.jkube.generator.javaexec.JavaExecGenerator;
import org.eclipse.jkube.kit.config.image.ImageConfiguration;
import org.eclipse.jkube.kit.common.util.JKubeProjectUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jkube.kit.common.Assembly;
import org.eclipse.jkube.kit.common.AssemblyConfiguration;
import org.eclipse.jkube.kit.common.AssemblyFile;
import org.eclipse.jkube.kit.common.AssemblyFileSet;
import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.Plugin;
import org.eclipse.jkube.kit.config.image.build.BuildConfiguration;
import org.eclipse.jkube.wildfly.jar.enricher.WildflyJARHealthCheckEnricher;
import static org.eclipse.jkube.wildfly.jar.enricher.WildflyJARHealthCheckEnricher.BOOTABLE_JAR_ARTIFACT_ID;
import static org.eclipse.jkube.wildfly.jar.enricher.WildflyJARHealthCheckEnricher.BOOTABLE_JAR_GROUP_ID;
import org.apache.commons.lang3.StringUtils;

public class WildflyJARGenerator extends JavaExecGenerator {
    static final String JBOSS_MAVEN_DIST = "jboss-maven-dist";
    static final String JBOSS_MAVEN_REPO = "jboss-maven-repo";
    static final String PLUGIN_OPTIONS = "plugin-options";
    static final String MAVEN_REPO_DIR = "server-maven-repo";
    static final String SERVER_DIR = "server";

    final Path localRepoCache;
    public WildflyJARGenerator(GeneratorContext context) {
        super(context, "wildfly-jar");
        JavaProject project = context.getProject();
        Plugin plugin = JKubeProjectUtil.getPlugin(project, BOOTABLE_JAR_GROUP_ID, BOOTABLE_JAR_ARTIFACT_ID);
        localRepoCache = Optional.ofNullable(plugin).
                map(Plugin::getConfiguration).
                map(c -> (Map<String, Object>) c.get(PLUGIN_OPTIONS)).
                map(options -> options.containsKey(JBOSS_MAVEN_DIST) && options.containsKey(JBOSS_MAVEN_REPO) ? options : null).
                map(options -> {
                   String dist = (String) options.get(JBOSS_MAVEN_DIST);
                   return dist == null || "true".equals(dist) ? (String) options.get(JBOSS_MAVEN_REPO) : null;
                }).map(Paths::get).orElse(null);
    }

    @Override
    public boolean isApplicable(List<ImageConfiguration> configs) {
        return shouldAddGeneratedImageConfiguration(configs)
                && JKubeProjectUtil.hasPlugin(getProject(),
                        WildflyJARHealthCheckEnricher.BOOTABLE_JAR_GROUP_ID, WildflyJARHealthCheckEnricher.BOOTABLE_JAR_ARTIFACT_ID);
    }

    @Override
    protected Map<String, String> getEnv(boolean isPrepackagePhase) {
        Map<String, String> ret = super.getEnv(isPrepackagePhase);
        // Switch off Prometheus agent until logging issue with WildFly Swarm is resolved
        // See:
        // - https://github.com/fabric8io/fabric8-maven-plugin/issues/1173
        // - https://issues.jboss.org/browse/THORN-1859
        ret.put("AB_PROMETHEUS_OFF", "true");
        ret.put("AB_OFF", "true");

        // In addition, there is no proper fix in Jolokia to detect that the Bootable JAR is started.
        // Can be workarounded by setting JAVA_OPTIONS to contain -Djboss.modules.system.pkgs=org.jboss.byteman
        ret.put("AB_JOLOKIA_OFF", "true");
        return ret;
    }

    @Override
    public List<AssemblyFileSet> addAdditionalFiles() {
        List<AssemblyFileSet> set = super.addAdditionalFiles();
        addLocalRepoCache(set);
        return set;
    }

    private void addLocalRepoCache(List<AssemblyFileSet> set) {
        if (localRepoCache != null) {
            Path repoDir = localRepoCache;
            if (!localRepoCache.isAbsolute()) {
                repoDir = getProject().getBaseDirectory().toPath().resolve(localRepoCache);
            }
            if (Files.notExists(repoDir)) {
               throw new RuntimeException("Error, WildFly bootable JAR generator can't retrieve "
                       + "generated maven local cache, directory " + repoDir + " doesn't exist."); 
            }
            set.add(AssemblyFileSet.builder()
                    .directory(repoDir.toFile())
                    .include("**")
                    .outputDirectory(new File(MAVEN_REPO_DIR))
                    .fileMode("0640")
                    .build());
        }
    }

    @Override
    protected List<String> getExtraJavaOptions() {
        List<String> properties = new ArrayList<>();
        properties.add("-Djava.net.preferIPv4Stack=true");
        if (localRepoCache != null) {
            properties.add("-Dmaven.repo.local=/deployments/" + MAVEN_REPO_DIR);
        }
        return properties;
    }
    
    @Override
    public List<ImageConfiguration> customize(List<ImageConfiguration> configs, boolean prePackagePhase) {
        if (isFatJar()) {
            return super.customize(configs, prePackagePhase);
        } else {
            final ImageConfiguration.ImageConfigurationBuilder imageBuilder = ImageConfiguration.builder();
            final BuildConfiguration.BuildConfigurationBuilder buildBuilder = BuildConfiguration.builder();
            addSchemaLabels(buildBuilder, log);
            addFrom(buildBuilder);
            if (!prePackagePhase) {
                try {
                    // Only add assembly if not in a pre-package phase where the referenced files
                    // won't be available.
                    buildBuilder.assembly(createServerAssembly(buildBuilder));
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
            // For JKube binary build, the JBOSS_HOME is expected to be set to the assemly directory. 
            Map<String, String> envMap = getServerEnv();
            envMap.put("JBOSS_HOME", "/opt/server");
            buildBuilder.env(envMap);
            addLatestTagIfSnapshot(buildBuilder);
            buildBuilder.workdir(getBuildWorkdir());
            buildBuilder.entryPoint(getBuildEntryPoint());
            imageBuilder
                    .name(getImageName())
                    .registry(getRegistry())
                    .alias(getAlias())
                    .build(buildBuilder.build());
            configs.add(imageBuilder.build());
            return configs;
        }
    }
    
    protected Map<String, String> getServerEnv() {
        List<String> properties = new ArrayList<>();
        if (localRepoCache != null) {
            properties.add("-Dmaven.repo.local=/opt/" + MAVEN_REPO_DIR);
        }
        Map<String, String> ret = new HashMap<>();
        if (!properties.isEmpty()) {
            // Use JAVA_OPTS in order to convey the local repo location to all JBoss scripts (CLI, ...) present in the image
            ret.put("JAVA_OPTS", StringUtils.join(properties.iterator(), " "));
        }
        return ret;
    }

    private AssemblyConfiguration createServerAssembly(BuildConfiguration.BuildConfigurationBuilder buildBuilder) throws IOException {
        final AssemblyConfiguration.AssemblyConfigurationBuilder builder = AssemblyConfiguration.builder();
        builder.targetDir("/opt");
        builder.excludeFinalOutputArtifact(true);
        addServerAssembly(builder);
        builder.name("server");
        return builder.build();
    }

    private void addServerAssembly(AssemblyConfiguration.AssemblyConfigurationBuilder builder) throws IOException {
        File buildDirectory = getProject().getBuildDirectory();
        File server = scanBuildDirectory(buildDirectory);
        if (server != null) {
            List<AssemblyFileSet> fileSets = new ArrayList<>();
            addLocalRepoCache(fileSets);
            fileSets.add(AssemblyFileSet.builder()
                    .directory(server)
                    .outputDirectory(new File(SERVER_DIR))
                    .include("**")
                    .exclude("**.sh")
                    .fileMode("0644")
                    .build());
            fileSets.add(AssemblyFileSet.builder()
                    .directory(server)
                    .outputDirectory(new File(SERVER_DIR))
                    .include("**.sh")
                    .fileMode("0755")
                    .build());

            builder.inline(Assembly.builder().fileSets(fileSets).build());
            builder.user("jboss:root:jboss");
        } else {
            log.warn("No server detected, make sure your image assembly configuration contains all the required"
                    + " dependencies for your application to run.");
        }
    }

    // Retrieve all files of the server.
    // Not actually used. Could be used to replace the 2 fileset inclusion.
     private static List<AssemblyFile> assembleServerFiles(Path rootDir) throws IOException {
         final List<AssemblyFile> files = new ArrayList<>();
        Files.walkFileTree(rootDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                        String fileMode = "0664";
                        if (file.getFileName().toString().endsWith(".sh")) {
                            fileMode = "0775";
                        }
                        
                        Path relative = rootDir.relativize(file);
                        Path relativeParent = relative.getParent();
                        File rootDir = new File("server");
                        files.add(AssemblyFile.builder().source(file.toFile()).
                                destName(file.getFileName().toString()).
                                fileMode(fileMode).
                                outputDirectory(relativeParent == null ? rootDir : new File(rootDir, relativeParent.toFile().toString())).
                                build());
                        return FileVisitResult.CONTINUE;
                    }
                });
        return files;
    }
     
    private File scanBuildDirectory(File build) throws IOException {
        if (!build.exists()) {
            return null;
        }
        List<Path> servers = Files.find(
                build.toPath(),
                1,
                (filePath, fileAttr) -> fileAttr.isDirectory()
                && !filePath.equals(build.toPath())
                && Files.exists(filePath.resolve("jboss-modules.jar"))
                && Files.exists(filePath.resolve("bin"))
                && Files.exists(filePath.resolve("standalone"))
                && Files.exists(filePath.resolve("modules"))
        ).collect(Collectors.toList());
        if (servers.isEmpty()) {
            log.warn("No server detected, make sure your image assembly configuration contains all the required"
                    + " dependencies for your application to run.");
            return null;
        }
        if (servers.size() > 1) {
            throw new RuntimeException("Multiple server exist, use the option to identify the right server" + servers);
        }
        return servers.get(0).toFile();
    }
}
