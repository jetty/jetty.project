//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.testers;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Helper class to test JPMS applications, that will be run in
 * a forked JVM with the parameters specified to {@link Builder}.</p>
 * <p>APIs can change without any further notice.</p>
 * <p>Usage:</p>
 * <pre>{@code
 * String jettyVersion = "12.0.0";
 * try (JPMSTester app = new JPMSTester.Builder(workDirPath)
 *     .jvmArgs("-Xmx1G")
 *     // Your application module-info source, will be compiled on-the-fly.
 *     .moduleInfo("""
 *         module app
 *         {
 *             requires ...;
 *             exports ...;
 *         }
 *         """)
 *     // Add the JPMS module dependencies required by your application.
 *     .addToModulePath("org.eclipse.jetty:jetty-jetty-client:" + jettyVersion)
 *     .mainClass(Main.class)
 *     .args(networkPort)
 *     .build())
 * {
 *     // Your main class will run and produce some output in console.
 *     assertTrue(app.awaitConsoleLogsFor("DONE", Duration.ofSeconds(10)));
 * }
 * }</pre>
 */
public class JPMSTester extends ProcessWrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(JPMSTester.class);

    private final Config config;

    private JPMSTester(Config config, Process process)
    {
        super(process);
        this.config = config;
    }

    /**
     * @return the configuration of this instance
     */
    public Config getConfig()
    {
        return config;
    }

    /**
     * <p>The configuration of a {@link JPMSTester}.</p>
     */
    public static class Config
    {
        private Path workDir;
        private List<String> jvmArgs;
        private String moduleInfo;
        private final List<Path> modulePaths = new ArrayList<>();
        private final List<Path> classPaths = new ArrayList<>();
        private Class<?> mainClass;
        private List<String> args;

        public List<String> getJVMArgs()
        {
            return jvmArgs == null ? List.of() : jvmArgs;
        }

        public Path getWorkingDirectory()
        {
            return workDir;
        }

        public String getModuleInfo()
        {
            return moduleInfo;
        }

        public List<Path> getModulePaths()
        {
            return modulePaths;
        }

        public List<Path> getClassPaths()
        {
            return classPaths;
        }

        public Class<?> getMainClass()
        {
            return mainClass;
        }

        public List<String> getArgs()
        {
            return args == null ? List.of() : args;
        }
    }

    /**
     * <p>A builder for {@link JPMSTester}.</p>
     */
    public static class Builder
    {
        private final MavenHelper mavenHelper = new MavenHelper();
        private final Config config = new Config();
        private Path classesDir;

        /**
         * <p>Creates a new instance with the specified root directory.</p>
         * <p>The root directory will be the root of possibly many application modules,
         * and will be used to create an application-specific subdirectory for each
         * JPMS applications, so that for example a client and a server JPMS applications
         * are grouped under the same root directory.</p>
         *
         * @param rootDir the root directory
         * @throws IOException if the application-specific subdirectory cannot be created
         * @see #build()
         */
        public Builder(Path rootDir) throws IOException
        {
            FS.ensureDirExists(rootDir);
            config.workDir = Files.createTempDirectory(rootDir, "jpms");
        }

        /**
         * @param jvmArgs the JVM arguments
         * @return this instance
         */
        public Builder jvmArgs(String... jvmArgs)
        {
            config.jvmArgs = List.of(jvmArgs);
            return this;
        }

        /**
         * @param classesDir the root directory of the compiles classes of this application
         * @return this instance
         */
        public Builder classesDirectory(Path classesDir)
        {
            this.classesDir = classesDir;
            return this;
        }

        /**
         * @param moduleInfo the source of this application {@code module-info.java}
         * @return this instance
         */
        public Builder moduleInfo(String moduleInfo)
        {
            config.moduleInfo = moduleInfo;
            return this;
        }

        /**
         * <p>Adds to the module-path the given Maven artifact.</p>
         *
         * @param mavenCoordinate the Maven coordinates of the artifact
         * @return this instance
         * @throws ArtifactResolutionException if the Maven artifact cannot be resolved
         */
        public Builder addToModulePath(String mavenCoordinate) throws ArtifactResolutionException
        {
            return addToModulePath(mavenHelper.resolveArtifact(mavenCoordinate));
        }

        /**
         * <p>Adds to the module-path the given path, either a directory or a jar file.</p>
         *
         * @param path the path or jar to add to the module-path
         * @return this instance
         */
        public Builder addToModulePath(Path path)
        {
            config.modulePaths.add(path);
            return this;
        }

        /**
         * <p>Adds to the class-path the given Maven artifact.</p>
         *
         * @param mavenCoordinate the Maven coordinates of the artifact
         * @return this instance
         * @throws ArtifactResolutionException if the Maven artifact cannot be resolved
         */
        public Builder addToClassPath(String mavenCoordinate) throws ArtifactResolutionException
        {
            return addToClassPath(mavenHelper.resolveArtifact(mavenCoordinate));
        }

        /**
         * <p>Adds to the class-path the given path, either a directory or a jar file.</p>
         *
         * @param path the path or jar to add to the class-path
         * @return this instance
         */
        public Builder addToClassPath(Path path)
        {
            config.classPaths.add(path);
            return this;
        }

        /**
         * <p>Specifies the main class of the JPMS application.</p>
         * <p>The main class must be in the module specified by
         * {@link #moduleInfo(String)} and publicly exported.</p>
         *
         * @param mainClass the JPMS application main class
         * @return this instance
         */
        public Builder mainClass(Class<?> mainClass)
        {
            config.mainClass = mainClass;
            return this;
        }

        /**
         * @param args the application arguments
         * @return this instance
         */
        public Builder args(String... args)
        {
            config.args = List.of(args);
            return this;
        }

        /**
         * <p>Builds a {@link JPMSTester} instance, forking a JVM with the parameters specified in this instance.</p>
         * <p>The {@code module-info.java} source specified via {@link #moduleInfo(String)} will be compiled and
         * saved in the application-specific subdirectory (see {@link #Builder(Path)}).</p>
         * <p>The forked JVM working directory will be the application-specific subdirectory.</p>
         *
         * @return a {@link JPMSTester} instance
         * @throws Exception in case of failures to either compile {@code module-info.java} or fork of the JVM
         */
        public JPMSTester build() throws Exception
        {
            if (config.getModuleInfo() == null)
                throw new IllegalArgumentException("missing module-info");

            if (classesDir == null)
                throw new IllegalArgumentException("missing classes directory");

            if (config.getMainClass() == null)
                throw new IllegalArgumentException("missing main class");

            Path workDir = config.getWorkingDirectory();
            IO.copyDir(classesDir, workDir);

            String modulePath = config.getModulePaths().stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
            if (!modulePath.isEmpty())
                modulePath += File.pathSeparator;
            modulePath += workDir.toString();

            String classPath =
                config.getClassPaths().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

            ModuleReference module = compileModuleInfo(modulePath, classPath);

            List<String> commands = new ArrayList<>();
            commands.add(Tester.getJavaExecutable("java"));
            commands.addAll(config.getJVMArgs());
            commands.add("--module-path");
            commands.add(modulePath);
            if (!classPath.isEmpty())
            {
                commands.add("--class-path");
                commands.add(classPath);
            }
            commands.add("--module");
            commands.add("%s/%s"
                .formatted(module.descriptor().name(), config.getMainClass().getName()));
            commands.addAll(config.getArgs());

            LOG.info("executing: " + String.join(" ", commands));

            ProcessBuilder processBuilder = new ProcessBuilder(commands);
            processBuilder.directory(workDir.toFile());
            return new JPMSTester(config, processBuilder.start());
        }

        private ModuleReference compileModuleInfo(String modulePath, String classPath) throws Exception
        {
            List<String> commands = new ArrayList<>();
            commands.add(Tester.getJavaExecutable("javac"));
            commands.add("--module-path");
            commands.add(modulePath);
            if (!classPath.isEmpty())
            {
                commands.add("--class-path");
                commands.add(classPath);
            }

            Path workDir = config.getWorkingDirectory();
            Path moduleInfoPath = workDir.resolve("module-info.java");
            Files.writeString(
                moduleInfoPath,
                config.getModuleInfo(),
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            commands.add(moduleInfoPath.toString());

            // This additional class is necessary to be able to compile module-info.java.
            // There should be one additional class for every exported package, but just
            // use the one derived from the main class for now.
            String packageName = config.getMainClass().getPackageName();
            Path bogusClassPath = workDir.resolve(packageName.replace('.', '/')).resolve("Bogus.java");
            Files.writeString(
                bogusClassPath,
                "package %s; class Bogus {}".formatted(packageName),
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            commands.add(bogusClassPath.toString());

            LOG.info("executing: " + String.join(" ", commands));

            ProcessBuilder processBuilder = new ProcessBuilder(commands);
            try (ProcessWrapper javac = new ProcessWrapper(processBuilder.start()))
            {
                javac.whenExit().orTimeout(10, TimeUnit.SECONDS).get();
            }
            if (!Files.exists(workDir.resolve("module-info.class")))
                throw new IllegalStateException("could not compile module-info");

            return ModuleFinder.of(workDir).findAll().stream().findAny().orElseThrow();
        }
    }
}
