//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Helper class to test the Jetty Distribution</p>.
 * <p>API can change without any further notice.</p>
 * <p>Usage:</p>
 * <pre>
 * // Create the distribution.
 * String jettyVersion = "9.4.14.v20181114";
 * DistributionTester distribution = DistributionTester.Builder.newInstance()
 *         .jettyVersion(jettyVersion)
 *         .jettyBase(Paths.get("demo-base"))
 *         .build();
 *
 * // The first run initializes the Jetty Base.
 * try (DistributionTester.Run run1 = distribution.start("--create-start-ini", "--add-modules=http2c,jsp,deploy"))
 * {
 *     assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
 *     assertEquals(0, run1.getExitValue());
 *
 *     // Install a web application.
 *     File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-simple-webapp:war:" + jettyVersion);
 *     distribution.installWarFile(war, "test");
 *
 *     // The second run starts the distribution.
 *     int port = 9090;
 *     try (DistributionTester.Run run = distribution.start("jetty.http.port=" + port))
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
 * </pre>
 */
public class JettyHomeTester
{
    private static final Logger LOGGER = LoggerFactory.getLogger(JettyHomeTester.class);

    private final Config config;

    private JettyHomeTester(Config config)
    {
        this.config = config;
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

    public Path getJettyBase()
    {
        return config.getJettyBase();
    }

    public Path getJettyHome()
    {
        return config.getJettyHome();
    }

    /**
     * Start the distribution with the arguments
     *
     * @param args arguments to use to start the distribution
     */
    public JettyHomeTester.Run start(List<String> args) throws Exception
    {
        File jettyBaseDir = getJettyBase().toFile();
        Path workDir = Files.createDirectories(jettyBaseDir.toPath().resolve("work"));

        List<String> commands = new ArrayList<>();
        commands.add(getJavaExecutable());
        commands.addAll(config.getJVMArgs());
        commands.add("-Djava.io.tmpdir=" + workDir.toAbsolutePath());
        int debugPort = Integer.getInteger("distribution.debug.port", 0);
        if (debugPort > 0)
        {
            commands.add("-Xdebug");
            commands.add("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=" + debugPort);
        }
        commands.add("-jar");
        commands.add(config.jettyHome.toAbsolutePath() + "/start.jar");

        args = new ArrayList<>(args);

        String mavenLocalRepository = config.getMavenLocalRepository();
        if (StringUtil.isNotBlank(mavenLocalRepository))
            mavenLocalRepository = System.getProperty("mavenRepoPath");
        if (StringUtil.isNotBlank(mavenLocalRepository))
            args.add("maven.local.repo=" + mavenLocalRepository);

        // if this JVM has `maven.repo.uri` defined, make sure to propagate it to child
        String remoteRepoUri = System.getProperty("maven.repo.uri");
        if (remoteRepoUri != null)
        {
            args.add("maven.repo.uri=" + remoteRepoUri);
        }

        commands.addAll(args);

        LOGGER.info("Executing: {}", commands);
        LOGGER.info("Working Dir: {}", jettyBaseDir.getAbsolutePath());

        ProcessBuilder pbCmd = new ProcessBuilder(commands);
        pbCmd.directory(jettyBaseDir);
        pbCmd.environment().putAll(config.env);
        Process process = pbCmd.start();

        return new Run(config, process);
    }

    /**
     * @return a free port chosen by the OS that can be used to listen to
     * @throws IOException if a free port is not available
     */
    public int freePort() throws IOException
    {
        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", 0));
            return server.getLocalPort();
        }
    }

    /**
     * Installs content from {@code src/test/resources/<testResourcePath>} into {@code ${jetty.base}/<baseResourcePath>}
     *
     * @param testResourcePath the location of the source file in {@code src/test/resources}
     * @param baseResourcePath the location of the destination file in {@code ${jetty.base}}
     * @throws IOException if unable to copy file
     */
    public void installBaseResource(String testResourcePath, String baseResourcePath) throws IOException
    {
        Path srcFile = MavenTestingUtils.getTestResourcePath(testResourcePath);
        Path destFile = config.jettyBase.resolve(baseResourcePath);

        Files.copy(srcFile, destFile);
    }

    /**
     * Installs in {@code ${jetty.base}/webapps} the given war file under the given context path.
     *
     * @param warFile the war file to install
     * @param context the context path
     * @return the path to the installed webapp exploded directory
     * @throws IOException if the installation fails
     */
    public Path installWarFile(File warFile, String context) throws IOException
    {
        //webapps
        Path webapps = config.jettyBase.resolve("webapps").resolve(context);
        if (!Files.exists(webapps))
            Files.createDirectories(webapps);
        unzip(warFile, webapps.toFile());
        return webapps;
    }

    /**
     * Resolves an artifact given its Maven coordinates.
     *
     * @param coordinates &lt;groupId>:&lt;artifactId>[:&lt;extension>[:&lt;classifier>]]:&lt;version>
     * @return the artifact
     * @see #installWarFile(File, String)
     */
    public File resolveArtifact(String coordinates) throws ArtifactResolutionException
    {
        RepositorySystem repositorySystem = newRepositorySystem();

        Artifact artifact = new DefaultArtifact(coordinates);

        RepositorySystemSession session = newRepositorySystemSession(repositorySystem);

        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(newRepositories());
        ArtifactResult artifactResult = repositorySystem.resolveArtifact(session, artifactRequest);

        artifact = artifactResult.getArtifact();
        return artifact.getFile();
    }

    private void init() throws Exception
    {
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

    private String getJavaExecutable()
    {
        String[] javaExecutables = new String[]{"java", "java.exe"};
        Path javaBinDir = Paths.get(System.getProperty("java.home")).resolve("bin");
        for (String javaExecutable : javaExecutables)
        {
            Path javaFile = javaBinDir.resolve(javaExecutable);
            if (Files.exists(javaFile) && Files.isRegularFile(javaFile))
                return javaFile.toAbsolutePath().toString();
        }
        return "java";
    }

    private void unzip(File zipFile, File output) throws IOException
    {
        try (InputStream fileInputStream = Files.newInputStream(zipFile.toPath());
             ZipInputStream zipInputStream = new ZipInputStream(fileInputStream))
        {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null)
            {
                if (entry.isDirectory())
                {
                    File dir = new File(output, entry.getName());
                    if (!Files.exists(dir.toPath()))
                    {
                        Files.createDirectories(dir.toPath());
                    }
                }
                else
                {
                    // Read zipEntry and write to a file.
                    File file = new File(output, entry.getName());
                    if (!Files.exists(file.getParentFile().toPath()))
                    {
                        Files.createDirectories(file.getParentFile().toPath());
                    }
                    try (OutputStream outputStream = Files.newOutputStream(file.toPath()))
                    {
                        IOUtil.copy(zipInputStream, outputStream);
                    }
                }
                // Get next entry
                entry = zipInputStream.getNextEntry();
            }
        }
    }

    private Path resolveHomeArtifact(String version) throws Exception
    {
        File artifactFile = resolveArtifact("org.eclipse.jetty:jetty-home:zip:" + version);

        // create tmp directory to unzip distribution
        Path homes = MavenTestingUtils.getTargetTestingPath("homes");
        FS.ensureDirExists(homes);
        Path tmp = Files.createTempDirectory(homes, "jetty_home_");
        File tmpFile = tmp.toFile();

        unzip(artifactFile, tmpFile);

        return tmp.resolve("jetty-home-" + version);
    }

    private RepositorySystem newRepositorySystem()
    {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler()
        {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception)
            {
                LOGGER.warn("Service creation failed for {} implementation {}: {}",
                    type, impl, exception.getMessage(), exception);
            }
        });

        return locator.getService(RepositorySystem.class);
    }

    private List<RemoteRepository> newRepositories()
    {
        List<RemoteRepository> remoteRepositories = new ArrayList<>(config.mavenRemoteRepositories.size() + 1);
        config.mavenRemoteRepositories.forEach((key, value) -> remoteRepositories.add(new RemoteRepository.Builder(key, "default", value).build()));
        remoteRepositories.add(newCentralRepository());
        return remoteRepositories;
    }

    private static RemoteRepository newCentralRepository()
    {
        return new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build();
    }

    private DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system)
    {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(config.mavenLocalRepository);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        session.setTransferListener(new LogTransferListener());
        session.setRepositoryListener(new LogRepositoryListener());

        return session;
    }

    public static class Config
    {
        private final Map<String, String> mavenRemoteRepositories = new HashMap<>();
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
            return String.format("%s@%x{jettyBase=%s, jettyHome=%s, jettyVersion=%s, mavenLocalRepository=%s, mavenRemoteRepositories=%s}",
                getClass().getSimpleName(),
                hashCode(),
                jettyBase,
                jettyHome,
                jettyVersion,
                mavenLocalRepository,
                mavenRemoteRepositories);
        }
    }

    private static class LogTransferListener extends AbstractTransferListener
    {
        // no op
    }

    private static class LogRepositoryListener extends AbstractRepositoryListener
    {
        @Override
        public void artifactDownloaded(RepositoryEvent event)
        {
            LOGGER.debug("distribution downloaded to {}", event.getFile());
        }

        @Override
        public void artifactResolved(RepositoryEvent event)
        {
            LOGGER.debug("distribution resolved to {}", event.getFile());
        }
    }

    /**
     * A distribution run wraps the process that started the Jetty distribution.
     */
    public static class Run implements Closeable
    {
        private final Config config;
        private final Process process;
        private final List<ConsoleStreamer> consoleStreamers = new ArrayList<>();
        private final Queue<String> logs = new ConcurrentLinkedQueue<>();

        private Run(Config config, Process process)
        {
            this.config = config;
            this.process = process;
            consoleStreamers.add(startPump("STDOUT", process.getInputStream()));
            consoleStreamers.add(startPump("STDERR", process.getErrorStream()));
        }

        public Config getConfig()
        {
            return config;
        }

        private ConsoleStreamer startPump(String mode, InputStream stream)
        {
            ConsoleStreamer pump = new ConsoleStreamer(stream);
            Thread thread = new Thread(pump, "ConsoleStreamer/" + mode);
            thread.start();
            return pump;
        }

        /**
         * Waits for the given time for the distribution process to stop.
         *
         * @param time the time to wait
         * @param unit the unit of time
         * @return true if the distribution process is terminated, false if the timeout elapsed
         * @throws InterruptedException if the wait is interrupted
         */
        public boolean awaitFor(long time, TimeUnit unit) throws InterruptedException
        {
            boolean result = process.waitFor(time, unit);
            if (result)
                stopConsoleStreamers();
            return result;
        }

        /**
         * @return the distribution process exit value
         * @throws IllegalThreadStateException if the distribution process is not terminated yet
         */
        public int getExitValue() throws IllegalThreadStateException
        {
            return process.exitValue();
        }

        /**
         * Stops the distribution process.
         *
         * @see #awaitFor(long, TimeUnit)
         */
        public void stop()
        {
            process.destroy();
            stopConsoleStreamers();
        }

        /**
         * Forcibly destroys the distribution process.
         */
        public void destroy()
        {
            process.destroyForcibly();
            stopConsoleStreamers();
        }

        private void stopConsoleStreamers()
        {
            consoleStreamers.forEach(ConsoleStreamer::stop);
        }

        @Override
        public void close()
        {
            stop();
            // delete the content of temporary base and home?
            //IO.delete(this.config.getJettyBase());
            //IO.delete(this.config.getJettyHome().getParent());
        }

        /**
         * Awaits the console logs to contain the given text, for the given amount of time.
         *
         * @param txt the text that must be present in the console logs
         * @param time the time to wait
         * @param unit the unit of time
         * @return true if the text was found, false if the timeout elapsed
         * @throws InterruptedException if the wait is interrupted
         */
        public boolean awaitConsoleLogsFor(String txt, long time, TimeUnit unit) throws InterruptedException
        {
            long end = System.nanoTime() + unit.toNanos(time);
            while (System.nanoTime() < end)
            {
                boolean result = logs.stream().anyMatch(s -> s.contains(txt));
                if (result)
                    return true;
                Thread.sleep(250);
            }
            return false;
        }

        /**
         * Awaits the logs file to contain the given text, for the given amount of time.
         *
         * @param logFile the log file to test
         * @param txt the text that must be present in the console logs
         * @param time the time to wait
         * @param unit the unit of time
         * @return true if the text was found, false if the timeout elapsed
         * @throws InterruptedException if the wait is interrupted
         */
        public boolean awaitLogsFileFor(Path logFile, String txt, long time, TimeUnit unit) throws InterruptedException
        {
            LogFileStreamer logFileStreamer = new LogFileStreamer(logFile);
            Thread thread = new Thread(logFileStreamer, "LogFileStreamer/" + logFile);
            thread.start();
            try
            {
                long end = System.nanoTime() + unit.toNanos(time);
                while (System.nanoTime() < end)
                {
                    boolean result = logs.stream().anyMatch(s -> s.contains(txt));
                    if (result)
                        return true;
                    Thread.sleep(250);
                }
                return false;
            }
            finally
            {
                logFileStreamer.stop();
            }
        }

        /**
         * Simple streamer for the console output from a Process
         */
        private class ConsoleStreamer implements Runnable
        {
            private final BufferedReader reader;
            private volatile boolean stop;

            public ConsoleStreamer(InputStream stream)
            {
                this.reader = new BufferedReader(new InputStreamReader(stream));
            }

            @Override
            public void run()
            {
                try
                {
                    String line;
                    while ((line = reader.readLine()) != null && !stop)
                    {
                        LOGGER.info(line);
                        logs.add(line);
                    }
                }
                catch (IOException ignore)
                {
                    // ignore
                }
                finally
                {
                    IO.close(reader);
                }
            }

            public void stop()
            {
                stop = true;
                IO.close(reader);
            }
        }

        private class LogFileStreamer implements Runnable
        {
            private RandomAccessFile inputFile;
            private volatile boolean stop;
            private final Path logFile;

            public LogFileStreamer(Path logFile)
            {
                this.logFile = logFile;
            }

            @Override
            public void run()
            {
                String currentLine;
                long pos = 0;
                while (!stop)
                {
                    try
                    {
                        inputFile = new RandomAccessFile(logFile.toFile(), "r");
                        inputFile.seek(pos);
                        if ((currentLine = inputFile.readLine()) != null)
                        {
                            logs.add(currentLine);
                        }
                        pos = inputFile.getFilePointer();
                    }
                    catch (IOException e)
                    {
                        //ignore
                    }
                    finally
                    {
                        IO.close(inputFile);
                    }
                }
            }

            public void stop()
            {
                stop = true;
                IO.close(inputFile);
            }
        }

        public Queue<String> getLogs()
        {
            return logs;
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
            config.mavenLocalRepository = mavenLocalRepository;
            return this;
        }

        /**
         * If needed to resolve the Jetty distribution from another Maven remote repositories
         *
         * @param id the id
         * @param url the Maven remote repository url
         * @return this Builder
         */
        public Builder addRemoteRepository(String id, String url)
        {
            config.mavenRemoteRepositories.put(id, url);
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
