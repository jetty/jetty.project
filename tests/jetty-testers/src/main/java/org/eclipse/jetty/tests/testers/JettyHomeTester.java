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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Helper class to test the Jetty Distribution</p>.
 * <p>API can change without any further notice.</p>
 * <p>Usage:</p>
 * <pre>{@code
 * // Create the distribution.
 * String jettyVersion = "12.0.0";
 * JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
 *         .jettyVersion(jettyVersion)
 *         .jettyBase(Paths.get("demo-base"))
 *         .build();
 *
 * // The first run initializes the Jetty Base.
 * try (JettyHomeTester.Run run1 = distribution.start("--create-start-ini", "--add-modules=http2c,jsp,deploy"))
 * {
 *     assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
 *     assertEquals(0, run1.getExitValue());
 *
 *     // Install a web application.
 *     Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-simple-webapp:war:" + jettyVersion);
 *     distribution.installWar(war, "test");
 *
 *     // The second run starts the distribution.
 *     int port = 9090;
 *     try (JettyHomeTester.Run run = distribution.start("jetty.http.port=" + port))
 *     {
 *         // Wait for Jetty to be fully started.
 *         assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));
 *
 *         // Make an HTTP request to the web application.
 *         HttpClient client = new HttpClient();
 *         client.start();
 *         ContentResponse response = client.GET("http://localhost:" + port + "/test/index.html");
 *         assertEquals(HttpStatus.OK_200, response.getStatus());
 *     }
 * }
 * }</pre>
 */
public class JettyHomeTester
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyHomeTester.class);

    private final MavenHelper mavenHelper = new MavenHelper();
    private final Config config;

    private JettyHomeTester(Config config)
    {
        this.config = config;
    }

    public Path getJettyBase()
    {
        return config.getJettyBase();
    }

    public Path getJettyHome()
    {
        return config.getJettyHome();
    }

    /**
     * Starts the distribution with the given arguments
     *
     * @param args arguments to use to start the distribution
     */
    public JettyHomeTester.Run start(String... args) throws Exception
    {
        return start(Arrays.asList(args));
    }

    /**
     * Start the distribution with the arguments
     *
     * @param args arguments to use to start the distribution
     */
    public JettyHomeTester.Run start(List<String> args) throws Exception
    {
        return start(List.of(), args);
    }

    /**
     * <p>Starts the distribution with the given JVM arguments, and program arguments.</p>
     * <p>JVM arguments allow for example to attach to the JVM with a debugger, and/or
     * set Jetty logging properties; specify respectively
     * {@code -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005}
     * and {@code -Dorg.eclipse.jetty.LEVEL=DEBUG}.</p>
     *
     * @param jvmArgs the JVM arguments
     * @param args the program arguments
     * @return the JVM process
     * @throws Exception if the JVM process cannot be executed
     */
    public JettyHomeTester.Run start(List<String> jvmArgs, List<String> args) throws Exception
    {
        File jettyBaseDir = getJettyBase().toFile();
        Path workDir = Files.createDirectories(jettyBaseDir.toPath().resolve("work"));

        List<String> commands = new ArrayList<>();
        commands.add(Tester.getJavaExecutable("java"));
        commands.addAll(config.getJVMArgs());
        commands.addAll(jvmArgs);
        commands.add("-Djava.io.tmpdir=" + workDir.toAbsolutePath());
        int debugPort = Integer.getInteger("distribution.debug.port", 0);
        if (debugPort > 0)
            commands.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:" + debugPort);
        commands.add("-jar");
        commands.add(config.jettyHome.toAbsolutePath() + "/start.jar");

        args = new ArrayList<>(args);

        String mavenLocalRepository = config.getMavenLocalRepository();
        if (StringUtils.isNotBlank(mavenLocalRepository))
            mavenLocalRepository = System.getProperty("mavenRepoPath");
        if (StringUtils.isNotBlank(mavenLocalRepository))
            args.add("maven.local.repo=" + mavenLocalRepository);

        String mavenOffline = System.getProperty("maven.offline", "false");
        if (StringUtils.isNotBlank(mavenOffline))
            args.add("maven.offline=" + mavenOffline);

        // if this JVM has `maven.repo.uri` defined, make sure to propagate it to child
        String remoteRepoUri = System.getProperty("maven.repo.uri");
        if (remoteRepoUri != null)
        {
            args.add("maven.repo.uri=" + remoteRepoUri);
        }

        commands.addAll(args);

        LOG.info("Executing: {}", commands);
        LOG.info("Working Dir: {}", jettyBaseDir.getAbsolutePath());

        ProcessBuilder pbCmd = new ProcessBuilder(commands);
        pbCmd.directory(jettyBaseDir);
        pbCmd.environment().putAll(config.env);
        Process process = pbCmd.start();

        return new Run(process, config);
    }

    /**
     * Installs in {@code ${jetty.base}/webapps} the given war file under the given context path.
     *
     * @param testResourcePath the location of the source file in {@code src/test/resources}
     * @param baseResourcePath the location of the destination file in {@code ${jetty.base}}
     * @param options optional CopyOption
     * @throws IOException if the installation fails
     */
    public void installBaseResource(String testResourcePath, String baseResourcePath, CopyOption... options) throws IOException
    {
        Path srcFile = MavenTestingUtils.getTestResourcePath(testResourcePath);
        Path destFile = config.jettyBase.resolve(baseResourcePath);
        Files.deleteIfExists(destFile);
        if (!Files.exists(destFile.getParent()))
            Files.createDirectories(destFile.getParent());

        Files.copy(srcFile, destFile, options);
    }

    /**
     * Installs in {@code ${jetty.base}/webapps} the given war file under the given context path.
     *
     * @param warPath the war file to install
     * @param context the context path
     * @return the path to the installed webapp exploded directory
     * @throws IOException if the installation fails
     */
    public Path installWar(Path warPath, String context) throws IOException
    {
        //webapps
        Path webapps = config.jettyBase.resolve("webapps").resolve(context);
        if (!Files.exists(webapps))
            Files.createDirectories(webapps);
        unzip(warPath, webapps);
        return webapps;
    }

    public Path resolveArtifact(String mavenCoordinate) throws ArtifactResolutionException
    {
        return mavenHelper.resolveArtifact(mavenCoordinate);
    }

    private void init() throws Exception
    {
        String jettyHomeStr = System.getProperty("jetty.home");
        if (jettyHomeStr != null && !jettyHomeStr.isEmpty())
        {
            Path jettyHome = Paths.get(jettyHomeStr);
            // test path exists
            if (Files.exists(jettyHome))
            {
                config.jettyHome = jettyHome;
            }
            else
            {
                throw new IllegalArgumentException("Ignore non existing Jetty home: " + jettyHomeStr);
            }
        }
        if (config.jettyHome == null)
            config.jettyHome = resolveHomeArtifact(config.getJettyVersion());

        if (config.jettyBase == null)
        {
            Path bases = MavenTestingUtils.getTargetTestingPath("bases");
            FS.ensureDirExists(bases);
            config.jettyBase = Files.createTempDirectory(bases, "jetty_base_");
        }
        else
        {
            if (!config.jettyBase.isAbsolute())
            {
                throw new IllegalStateException("Jetty Base is not an absolute path: " + config.jettyBase);
            }
        }
    }

    public static void unzip(Path archive, Path outputDir) throws IOException
    {
        if (!Files.exists(outputDir))
            throw new FileNotFoundException("Directory does not exist: " + outputDir);

        if (!Files.isDirectory(outputDir))
            throw new FileNotFoundException("Not a directory: " + outputDir);

        Map<String, String> env = new HashMap<>();
        env.put("releaseVersion", null); // no MultiRelease Jar file behaviors

        URI outputDirURI = outputDir.toUri();
        URI archiveURI = URI.create("jar:" + archive.toUri().toASCIIString());

        try (FileSystem fs = FileSystems.newFileSystem(archiveURI, env))
        {
            Path root = fs.getPath("/");
            int archiveURISubIndex = root.toUri().toASCIIString().indexOf("!/") + 2;
            try (Stream<Path> entriesStream = Files.walk(root))
            {
                // ensure proper unpack order (eg: directories before files)
                List<Path> sorted = entriesStream
                    .sorted()
                    .toList();

                for (Path path : sorted)
                {
                    URI entryURI = path.toUri();
                    String subURI = entryURI.toASCIIString().substring(archiveURISubIndex);
                    URI outputPathURI = outputDirURI.resolve(subURI);
                    Path outputPath = Path.of(outputPathURI);
                    if (Files.isDirectory(path))
                    {
                        if (!Files.exists(outputPath))
                            Files.createDirectory(outputPath);
                    }
                    else
                    {
                        Files.copy(path, outputPath);
                    }
                }
            }
        }
        catch (FileSystemAlreadyExistsException | FileAlreadyExistsException e)
        {
            LOG.warn("ignore {}: archiveURI {}, outputDir {}", e.getClass().getSimpleName(), archiveURI, outputDir);
        }
    }

    private Path resolveHomeArtifact(String version) throws Exception
    {
        Path artifactFile = mavenHelper.resolveArtifact("org.eclipse.jetty:jetty-home:zip:" + version);
        Path homes = MavenTestingUtils.getTargetTestingPath("homes");
        FS.ensureDirExists(homes);
        Path tmp = Files.createDirectories(homes.resolve(Long.toString(artifactFile.toFile().lastModified())));
        Path home = tmp.resolve("jetty-home-" + version);
        if (!Files.exists(home))
            unzip(artifactFile, tmp);
        return home;
    }

    public static class Config
    {
        private Path jettyBase;
        private Path jettyHome;
        private String jettyVersion;
        private String mavenLocalRepository = System.getProperty("mavenRepoPath", System.getProperty("user.home") + "/.m2/repository");
        private List<String> jvmArgs = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();

        public Path getJettyBase()
        {
            return jettyBase;
        }

        public Path getJettyHome()
        {
            return jettyHome;
        }

        public String getJettyVersion()
        {
            return jettyVersion;
        }

        public String getMavenLocalRepository()
        {
            return mavenLocalRepository;
        }

        public List<String> getJVMArgs()
        {
            return Collections.unmodifiableList(jvmArgs);
        }

        public Map<String, String> getEnv()
        {
            return Collections.unmodifiableMap(env);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{jettyBase=%s, jettyHome=%s, jettyVersion=%s, mavenLocalRepository=%s}",
                getClass().getSimpleName(),
                hashCode(),
                jettyBase,
                jettyHome,
                jettyVersion,
                mavenLocalRepository);
        }
    }

    /**
     * A distribution run wraps the process that started the Jetty distribution.
     */
    public static class Run extends ProcessWrapper
    {
        private final Config config;

        private Run(Process process, Config config)
        {
            super(process);
            this.config = config;
        }

        public Config getConfig()
        {
            return config;
        }
    }

    public static class Builder
    {
        private final Config config = new Config();

        private Builder()
        {
        }

        /**
         * @param jettyVersion the version to use (format: 9.4.14.v20181114 9.4.15-SNAPSHOT).
         * The distribution will be downloaded from local repository or remote
         * @return this Builder
         */
        public Builder jettyVersion(String jettyVersion)
        {
            config.jettyVersion = jettyVersion;
            return this;
        }

        /**
         * @param jettyHome Path to the local exploded jetty distribution
         * if configured the jettyVersion parameter will not be used
         * @return this Builder
         */
        public Builder jettyHome(Path jettyHome)
        {
            config.jettyHome = jettyHome;
            return this;
        }

        /**
         * <p>Sets the path for the Jetty Base directory.</p>
         * <p>If the path is relative, it will be resolved against the Jetty Home directory.</p>
         *
         * @param jettyBase Path to the local Jetty Base directory
         * @return this Builder
         */
        public Builder jettyBase(Path jettyBase)
        {
            config.jettyBase = jettyBase;
            return this;
        }

        /**
         * @param mavenLocalRepository Path to the local maven repository
         * @return this Builder
         */
        public Builder mavenLocalRepository(String mavenLocalRepository)
        {
            if (StringUtils.isBlank(mavenLocalRepository))
                return this;

            config.mavenLocalRepository = mavenLocalRepository;
            return this;
        }

        /**
         * @param jvmArgs the jvm args to add
         * @return this Builder
         */
        public Builder jvmArgs(List<String> jvmArgs)
        {
            config.jvmArgs = jvmArgs;
            return this;
        }

        /**
         * @param env the env to add
         * @return this Builder
         */
        public Builder env(Map<String, String> env)
        {
            config.env = env;
            return this;
        }

        /**
         * @return an empty instance of Builder
         */
        public static Builder newInstance()
        {
            return new Builder();
        }

        /**
         * @return a new configured instance of {@link JettyHomeTester}
         */
        public JettyHomeTester build() throws Exception
        {
            JettyHomeTester tester = new JettyHomeTester(config);
            tester.init();
            return tester;
        }
    }
}
