//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.FileUtils;
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
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>
 * Please note this class is use for Jetty Distribution testing.
 * <b>So API can change without any further notice.</b>
 * </p>
 */
public class DistributionTester implements Closeable
{

    private static final Logger LOGGER = Log.getLogger(DistributionTester.class);

    private Process pid;
    private URI baseUri;
    private String jmxUrl;
    private Config config;
    private List<ConsoleStreamer> consoleStreamers = new ArrayList<>();
    private List<String> logs = new ArrayList<>();
    private HttpClient httpClient;

    private static class Config
    {
        private long waitStartTime = 60;

        private long maxWaitToStop = 60000;

        private TimeUnit timeUnit = TimeUnit.SECONDS;

        private Path jettyBase;

        private File jettyHomeDir;

        private String jettyVersion;
        private String jettyHome;
        private String mavenLocalRepository = System.getProperty("user.home") + "/.m2/repository";
        private Map<String, String> mavenRemoteRepositories = new HashMap<>();
    }


    private DistributionTester(Config config) {
        this.config = config;
    }


    /**
     * start the instance with no arguments
     */
    public DistributionTester start() throws Exception
    {
        start(Collections.emptyList());
        return this;
    }

    /**
     * start the instance with the arguments
     *
     * @param args arguments to use to start the distribution
     */
    public DistributionTester start(String... args) throws Exception
    {
        start(Arrays.asList(args));
        return this;
    }

    /**
     * start the instance with the arguments
     *
     * @param args arguments to use to start the distribution
     */
    public DistributionTester start(List<String> args)
            throws Exception
    {

        // do we want to be sure and use "--testing-mode" to not break surefire with a System.exit ???

        logs.clear();

        List<String> commands = new ArrayList<>();
        commands.add(getJavaBin());

        commands.add("-Djetty.home=" + config.jettyHomeDir.getAbsolutePath());
        commands.add("-Djetty.base=" + config.jettyBase.toAbsolutePath().toString());

        commands.add("-jar");
        commands.add(config.jettyHomeDir.getAbsolutePath() + "/start.jar");
        commands.add("jetty.http.port=0");

        commands.addAll(args);

        ProcessBuilder pbCmd = new ProcessBuilder(commands);
        pbCmd.directory(config.jettyHomeDir);

        LOGGER.info("Executing: {}", commands);
        LOGGER.info("Working Dir: {}", config.jettyHomeDir.getAbsolutePath());

        pbCmd = new ProcessBuilder(commands);
        pid = pbCmd.start();

        ConsoleParser parser = new ConsoleParser();
        List<String[]> jmxList = parser.newPattern("JMX Remote URL: (.*)", 0);
        // Started ServerConnector@76f2bbc1{HTTP/1.1,[http/1.1]}{0.0.0.0:50214}
        List<String[]> connList =
                parser.newPattern("[A-Za-z]*Connector@.*\\{.*\\}\\{(.*)\\:([0-9]*)}", 1);

        consoleStreamers.add(startPump("STDOUT", parser, this.pid.getInputStream()));
        consoleStreamers.add(startPump("STDERR", parser, this.pid.getErrorStream()));

        try {
            long start = System.currentTimeMillis();
            parser.waitForDone(config.waitStartTime, config.timeUnit);
            LOGGER.info("wait start {}", System.currentTimeMillis() - start);


            if (!jmxList.isEmpty()) {
                this.jmxUrl = jmxList.get(0)[0];
                LOGGER.info("## Found JMX connector at {}", this.jmxUrl);
            }

            if (!connList.isEmpty()) {
                String[] params = connList.get(0);
                if (params.length == 2) {
                    this.baseUri = URI.create("http://localhost:" + params[1]);
                }
                LOGGER.info("## Found Jetty connector at port: {}", params[1]);
            }

        } catch (InterruptedException e) {
            pid.destroy();
        }
        return this;
    }

    public void installWarFile(File warFile, String context) throws IOException
    {
        //webapps
        Path webapps = Paths.get(config.jettyBase.toString(), "webapps", context);
        if (!Files.exists(webapps))
        {
            Files.createDirectories(webapps);
        }
        unzip(warFile, webapps.toFile());
    }

    //---------------------------------------
    // Assert methods
    //---------------------------------------

    public DistributionTester assertLogsContains(String txt)
    {
        assertTrue(logs.stream().filter(s -> StringUtils.contains(s, txt)).count() > 0);
        return this;
    }


    public DistributionTester assertUrlStatus(String url, int expectedStatus)
    {
        try
        {
            ContentResponse contentResponse = httpClient.GET(getBaseUri() + url);
            int status = contentResponse.getStatus();
            assertEquals(expectedStatus, status, () -> "status not " + expectedStatus + " but " + status);
        } catch (InterruptedException | ExecutionException | TimeoutException e)
        {
            fail(e.getMessage(), e);
        }
        return this;
    }

    public DistributionTester assertUrlContains(String url, String content)
    {
        try
        {
            ContentResponse contentResponse = httpClient.GET(getBaseUri() + url);
            String contentResponseStr = contentResponse.getContentAsString();
            assertTrue(StringUtils.contains(contentResponseStr, content), () -> "content not containing '" + content + "'");
        } catch (InterruptedException | ExecutionException | TimeoutException e)
        {
            fail(e.getMessage(), e);
        }
        return this;
    }

    /**
     * @param coordinates <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>
     * @return the artifact
     */
    public File resolveArtifact(String coordinates) throws ArtifactResolutionException
    {
        RepositorySystem repositorySystem = newRepositorySystem();

        Artifact artifact = new DefaultArtifact(coordinates);

        RepositorySystemSession session = newRepositorySystemSession(repositorySystem);

        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(newRepositories(repositorySystem, newRepositorySystemSession(repositorySystem)));
        ArtifactResult artifactResult = repositorySystem.resolveArtifact(session, artifactRequest);

        artifact = artifactResult.getArtifact();
        return artifact.getFile();
    }

    /**
     * @return the directory used as JettyBase
     */
    public Path getJettyBase()
    {
        return config.jettyBase;
    }

    /**
     * @return the {@link URI} to use to access the jetty instance (random port has been detected)
     */
    public URI getBaseUri()
    {
        return baseUri;
    }

    /**
     * @return the connection to use to access JMX (if configured)
     */
    public String getJmxUrl()
    {
        return jmxUrl;
    }

    /**
     * @return DistributionTester setup jettyHome directory and start httpClient.
     */
    private DistributionTester initialise() throws Exception
    {
        if (config.jettyBase == null)
        {
            config.jettyBase = Files.createTempDirectory("jetty_base_test");
            config.jettyBase.toFile().deleteOnExit();
        }

        if (StringUtils.isNotEmpty(config.jettyHome))
        {
            config.jettyHomeDir = Paths.get(config.jettyHome).toFile();
        } else
        {
            config.jettyHomeDir = resolveDistribution(config.jettyVersion);
        }

        this.httpClient = new HttpClient();
        httpClient.start();
        return this;
    }


    private ConsoleStreamer startPump(String mode, ConsoleParser parser, InputStream inputStream)
    {
        ConsoleStreamer pump = new ConsoleStreamer(mode, inputStream);
        pump.setParser(parser);
        Thread thread = new Thread(pump, "ConsoleStreamer/" + mode);
        thread.start();
        return pump;
    }

    /**
     * Simple streamer for the console output from a Process
     */
    private class ConsoleStreamer
            implements Runnable
    {
        private String mode;

        private BufferedReader reader;

        private ConsoleParser parser;

        private volatile boolean stop;

        public ConsoleStreamer(String mode, InputStream is)
        {
            this.mode = mode;
            this.reader = new BufferedReader(new InputStreamReader(is));
        }

        public void setParser(ConsoleParser connector)
        {
            this.parser = connector;
        }

        @Override
        public void run()
        {
            String line;
            try {
                while ((line = reader.readLine()) != null && !stop) {
                    if (parser != null) {
                        parser.parse(line);
                    }
                    // using LOGGER generates too long lines..
                    //LOGGER.info("[{}] {}",mode, line);
                    System.out.println("[" + mode + "] " + line);
                    DistributionTester.this.logs.add(line);
                }
            } catch (IOException ignore) {
                // ignore
            } finally {
                IO.close(reader);
            }
        }
    }

    private static class ConsoleParser
    {
        private List<ConsolePattern> patterns = new ArrayList<>();

        private CountDownLatch latch;

        private int count;

        public List<String[]> newPattern(String exp, int cnt)
        {
            ConsolePattern pat = new ConsolePattern(exp, cnt);
            patterns.add(pat);
            count += cnt;
            return pat.getMatches();
        }

        public void parse(String line)
        {
            for (ConsolePattern pat : patterns)
            {
                Matcher mat = pat.getMatcher(line);
                if (mat.find())
                {
                    int num = 0, count = mat.groupCount();
                    String[] match = new String[count];
                    while (num++ < count)
                    {
                        match[num - 1] = mat.group(num);
                    }
                    pat.getMatches().add(match);

                    if (pat.getCount() > 0)
                    {
                        getLatch().countDown();
                    }
                }
            }
        }

        public void waitForDone(long timeout, TimeUnit unit)
                throws InterruptedException
        {
            getLatch().await(timeout, unit);
        }

        private CountDownLatch getLatch()
        {
            synchronized (this)
            {
                if (latch == null)
                {
                    latch = new CountDownLatch(count);
                }
            }
            return latch;
        }
    }

    private static class ConsolePattern
    {
        private Pattern pattern;

        private List<String[]> matches;

        private int count;

        ConsolePattern(String exp, int cnt)
        {
            pattern = Pattern.compile(exp);
            matches = new ArrayList<>();
            count = cnt;
        }

        public Matcher getMatcher(String line)
        {
            return pattern.matcher(line);
        }

        public List<String[]> getMatches()
        {
            return matches;
        }

        public int getCount()
        {
            return count;
        }
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
                    if (!Files.exists(dir.toPath())) {
                        Files.createDirectories(dir.toPath());
                    }
                } else
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

    //---------------------------------------
    // Maven Utils methods
    //---------------------------------------

    private File resolveDistribution(String version) throws Exception
    {
        File artifactFile = resolveArtifact("org.eclipse.jetty:jetty-distribution:zip:" + version);

        // create tmp directory to unzip distribution
        Path tmp = Files.createTempDirectory("jetty_test");
        tmp.toFile().deleteOnExit();

        unzip(artifactFile, tmp.toFile());
        return new File(tmp.toFile(), "jetty-distribution-" + version);
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

    private List<RemoteRepository> newRepositories(RepositorySystem system, RepositorySystemSession session)
    {
        List<RemoteRepository> remoteRepositories = new ArrayList<>(config.mavenRemoteRepositories.size() + 1);
        config.mavenRemoteRepositories.entrySet().stream().forEach( stringStringEntry ->
            remoteRepositories.add(new RemoteRepository.Builder(stringStringEntry.getKey(), "default", stringStringEntry.getValue()).build())
        );
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

    private static class LogTransferListener extends AbstractTransferListener
    {
        // no op
    }

    private static class LogRepositoryListener extends AbstractRepositoryListener
    {

        @Override
        public void artifactDownloaded(RepositoryEvent event) {
            LOGGER.debug("distribution downloaded to {}", event.getFile());
        }

        @Override
        public void artifactResolved(RepositoryEvent event) {
            LOGGER.debug("distribution resolved to {}", event.getFile());
        }
    }


    private String getJavaBin()
    {
        String javaexes[] = new String[]{"java", "java.exe"};

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir, FS.separators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile()) {
                return javabin.getAbsolutePath();
            }
        }
        return "java";
    }

    /**
     * Stop the distribution
     */
    public void stop()
            throws IOException
    {
        long start = System.currentTimeMillis();
        while (this.pid.isAlive() && (System.currentTimeMillis() - start < config.maxWaitToStop))
        {
            this.pid.destroy();
            if (this.pid.isAlive())
            {
                // wait a bit to try again
                try
                {
                    Thread.sleep(500);
                } catch (InterruptedException e)
                {
                    throw new IOException(e.getMessage(),e);
                }
            }
        }
        // still alive so force stop
        if (this.pid.isAlive())
        {
            LOGGER.info("still alive so force destroy process");
            this.pid.destroyForcibly();
        }
        consoleStreamers.forEach(consoleStreamer -> consoleStreamer.stop=true);
    }

    /**
     * Method to use in finally block of a test and when using @After in a unit test.
     * if running, it stops the distribution.
     * Cleanup JettyBase and JettyHome directories
     */
    public void close() throws IOException
    {
        stop();
        if (httpClient != null && httpClient.isRunning()) {
            try
            {
                httpClient.stop();
            } catch (Exception e)
            {
                throw new IOException(e.getMessage(),e);
            }
        }
        if (Files.exists(config.jettyBase))
        {
            // cleanup jetty base
            IO.delete(config.jettyBase.toFile());
        }
        if (Files.exists(config.jettyHomeDir.toPath()))
        {
            // cleanup jetty distribution
            IO.delete(config.jettyHomeDir);
        }
    }


    //---------------------------------------
    // Builder class
    //---------------------------------------
    public static class Builder
    {
        private Builder()
        {
            // no op
        }

        private Path jettyBase;

        private String jettyVersion;

        private String jettyHome;

        private String mavenLocalRepository;

        private long waitStartTime = 60;

        private long maxWaitToStop = 60000;

        private Map<String, String> mavenRemoteRepositories = new HashMap<>();

        /**
         * @param jettyVersion the version to use (format: 9.4.14.v20181114 9.4.15-SNAPSHOT).
         * The distribution will be downloaded from local repository or remote
         * @return the {@link Builder}
         */
        public Builder jettyVersion(String jettyVersion)
        {
            this.jettyVersion = jettyVersion;
            return this;
        }

        /**
         * @param jettyHome Path to the local exploded jetty distribution
         * if configured the jettyVersion parameter will not be used
         * @return the {@link Builder}
         */
        public Builder jettyHome(String jettyHome)
        {
            this.jettyHome = jettyHome;
            return this;
        }

        /**
         * @param mavenLocalRepository Path to the local maven repository
         * @return the {@link Builder}
         */
        public Builder mavenLocalRepository(String mavenLocalRepository)
        {
            this.mavenLocalRepository = mavenLocalRepository;
            return this;
        }

        /**
         * @param waitStartTime the maximum time in seconds to wait the start of the distribution
         * @return the {@link Builder}
         */
        public Builder waitStartTime(long waitStartTime)
        {
            this.waitStartTime = waitStartTime;
            return this;
        }

        /**
         * @param maxWaitToStop the maximum time in seconds to wait after stop the distribution
         * process before forcing the stop.
         * @return the {@link Builder}
         */
        public Builder maxWaitToStop(long maxWaitToStop)
        {
            this.maxWaitToStop = maxWaitToStop;
            return this;
        }

        /**
         * If needed to resolve JettyDistribtion from another Maven remote repositories
         *
         * @param id the id
         * @param url the Maven remote repository url
         * @return the {@link Builder}
         */
        public Builder addRemoteRepository(String id, String url)
        {
            this.mavenRemoteRepositories.put(id, url);
            return this;
        }

        /**
         * @return an empty instance of {@link Builder}
         */
        public static Builder newInstance()
        {
            return new Builder();
        }

        /**
         * @return a new configured instance of {@link DistributionTester}
         */
        public DistributionTester build()
                throws Exception
        {
            Config config = new Config();
            config.jettyBase = jettyBase;
            config.jettyVersion = jettyVersion;
            config.jettyHome = jettyHome;
            config.mavenLocalRepository = mavenLocalRepository;
            config.waitStartTime = waitStartTime;
            config.maxWaitToStop = maxWaitToStop;
            if (!this.mavenRemoteRepositories.isEmpty())
            {
                config.mavenRemoteRepositories.putAll(this.mavenRemoteRepositories);
            }
            return new DistributionTester(config).initialise();
        }
    }
}
