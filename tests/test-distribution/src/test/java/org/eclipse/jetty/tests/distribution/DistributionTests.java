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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.http.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.tests.distribution.openid.OpenIdProvider;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.unixsocket.server.UnixSocketConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistributionTests extends AbstractJettyHomeTest
{
    @Test
    public void testStartStop() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testQuickStartGenerationAndRun() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=resources,server,http,webapp,deploy,jsp,servlet,servlets,quickstart"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            try (JettyHomeTester.Run run2 = distribution.start("jetty.quickstart.mode=GENERATE"))
            {
                assertTrue(run2.awaitConsoleLogsFor("QuickStartGeneratorConfiguration:main: Generated", 10, TimeUnit.SECONDS));
                Path unpackedWebapp = distribution.getJettyBase().resolve("webapps").resolve("test");
                assertTrue(Files.exists(unpackedWebapp));
                Path webInf = unpackedWebapp.resolve("WEB-INF");
                assertTrue(Files.exists(webInf));
                Path quickstartWebXml = webInf.resolve("quickstart-web.xml");
                assertTrue(Files.exists(quickstartWebXml));
                assertNotEquals(0, Files.size(quickstartWebXml));

                int port = distribution.freePort();

                try (JettyHomeTester.Run run3 = distribution.start("jetty.http.port=" + port, "jetty.quickstart.mode=QUICKSTART"))
                {
                    assertTrue(run3.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                    startHttpClient();
                    ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("JSP Examples"));
                    assertThat(response.getContentAsString(), not(containsString("<%")));
                }
            }
        }
    }

    @Test
    public void testSimpleWebAppWithJSPandJSTL() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-start-ini",
            "--approve-all-licenses",
            "--add-modules=resources,server,http,webapp,deploy,jsp,jstl,jmx,servlet,servlets"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Verify that --create-start-ini works
            assertTrue(Files.exists(jettyBase.resolve("start.ini")));

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/jstl.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    public void testSimpleWebAppWithJSPOnModulePath() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            String[] args2 = {
                "--jpms",
                "jetty.http.port=" + port
            };
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    public void testSimpleWebAppWithJSPOverH2C() throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(false);
    }

    @Test
    public void testSimpleWebAppWithJSPOverH2() throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(true);
    }

    private void testSimpleWebAppWithJSPOverHTTP2(boolean ssl) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--add-modules=jsp,deploy," + (ssl ? "http2,test-keystore" : "http2c")
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            String portProp = ssl ? "jetty.ssl.port" : "jetty.http.port";
            try (JettyHomeTester.Run run2 = distribution.start(portProp + "=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                ClientConnector connector = new ClientConnector();
                connector.setSslContextFactory(new SslContextFactory.Client(true));
                HTTP2Client h2Client = new HTTP2Client(connector);
                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP2(h2Client)));
                ContentResponse response = client.GET((ssl ? "https" : "http") + "://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "jnr not supported on windows")
    public void testUnixSocket() throws Exception
    {
        String dir = System.getProperty("jetty.unixdomain.dir");
        assertNotNull(dir);
        Path sockFile = Files.createTempFile(Path.of(dir), "unix_", ".sock");
        assertTrue(sockFile.toAbsolutePath().toString().length() < UnixSocketConnector.MAX_UNIX_SOCKET_PATH_LENGTH, "Unix-Domain path too long");
        Files.delete(sockFile);

        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--add-modules=unixsocket-http,deploy,jsp",
            "--approve-all-licenses"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            // Give it time to download the dependencies
            assertTrue(run1.awaitFor(30, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            try (JettyHomeTester.Run run2 = distribution.start("jetty.unixsocket.path=" + sockFile))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverUnixSockets(sockFile.toString())));
                ContentResponse response = client.GET("http://localhost/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
        finally
        {
            Files.deleteIfExists(sockFile);
        }
    }

    @Test
    public void testLog4j2ModuleWithSimpleWebAppWithJSP() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=resources,server,http,webapp,deploy,jsp,servlet,servlets,logging-log4j2"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());
            assertTrue(Files.exists(jettyBase.resolve("resources/log4j2.xml")));

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
                assertTrue(Files.exists(jettyBase.resolve("resources/log4j2.xml")));
            }
        }
        finally
        {
            IO.delete(jettyBase.toFile());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void testWebsocketClientInWebappProvidedByServer(String scheme) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String module = "https".equals(scheme) ? "test-keystore," + scheme : scheme;
        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,webapp,deploy,jsp,jmx,servlet,servlets,websocket,websocket-jetty-client," + module,
            };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty.tests:test-websocket-client-provided-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test");

            int port = distribution.freePort();
            String[] args2 = {
                "jetty.http.port=" + port,
                "jetty.ssl.port=" + port,
                // "jetty.server.dumpAfterStart=true",
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                // We should get the correct configuration from the jetty-websocket-httpclient.xml file.
                startHttpClient(scheme.equals("https"));
                URI serverUri = URI.create(scheme + "://localhost:" + port + "/test");
                ContentResponse response = client.GET(serverUri);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                String content = response.getContentAsString();
                assertThat(content, containsString("WebSocketEcho: success"));
                assertThat(content, containsString("ConnectTimeout: 4999"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void testWebsocketClientInWebapp(String scheme) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String module = "https".equals(scheme) ? "test-keystore," + scheme : scheme;
        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,webapp,deploy,jsp,jmx,servlet,servlets,websocket," + module
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty.tests:test-websocket-client-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test");

            int port = distribution.freePort();
            String[] args2 = {
                "jetty.http.port=" + port,
                "jetty.ssl.port=" + port,
                // "jetty.server.dumpAfterStart=true",
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                // We should get the correct configuration from the jetty-websocket-httpclient.xml file.
                startHttpClient(scheme.equals("https"));
                URI serverUri = URI.create(scheme + "://localhost:" + port + "/test");
                ContentResponse response = client.GET(serverUri);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                String content = response.getContentAsString();
                assertThat(content, containsString("WebSocketEcho: success"));
                assertThat(content, containsString("ConnectTimeout: 4999"));
            }
        }
    }

    @Test
    @Tag("external")
    public void testDownload() throws Exception
    {
        Path jettyBase = Files.createTempDirectory("jetty_base");
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String outPath = "etc/maven-metadata.xml";
        String[] args1 = {
            "--download=https://repo1.maven.org/maven2/org/eclipse/jetty/maven-metadata.xml|" + outPath
        };
        try (JettyHomeTester.Run run = distribution.start(args1))
        {
            assertTrue(run.awaitConsoleLogsFor("Base directory was modified", 110, TimeUnit.SECONDS));
            Path target = jettyBase.resolve(outPath);
            assertTrue(Files.exists(target), "could not create " + target);
        }
    }

    @Test
    public void testWebAppWithProxyAndJPMS() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--add-modules=http,webapp,deploy,resources"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path logFile = distribution.getJettyBase().resolve("resources").resolve("jetty-logging.properties");
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE))
            {
                writer.write("org.eclipse.jetty.LEVEL=INFO");
            }

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-proxy-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "proxy");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port, "jetty.server.dumpAfterStart=true"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP(1)));
                ContentResponse response = client.GET("http://localhost:" + port + "/proxy/current/");
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    /**
     * This reproduces some classloading issue with MethodHandles in JDK14-110, This has been fixed in JDK16.
     *
     * @throws Exception if there is an error during the test.
     * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8244090">JDK-8244090</a>
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "--jpms"})
    @DisabledOnJre({JRE.JAVA_14, JRE.JAVA_15})
    public void testSimpleWebAppWithWebsocket(String arg) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();
        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets,websocket"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty.tests:test-websocket-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test1");
            distribution.installWarFile(webApp, "test2");

            int port = distribution.freePort();
            String[] args2 = {
                arg,
                "jetty.http.port=" + port//,
                //"jetty.server.dumpAfterStart=true"
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertFalse(run2.getLogs().stream().anyMatch(s -> s.contains("LinkageError")));

                startHttpClient();
                WebSocketClient wsClient = new WebSocketClient(client);
                wsClient.start();
                URI serverUri = URI.create("ws://localhost:" + port);

                // Verify /test1 is able to establish a WebSocket connection.
                WsListener webSocketListener = new WsListener();
                Session session = wsClient.connect(webSocketListener, serverUri.resolve("/test1")).get(10, TimeUnit.SECONDS);
                session.getRemote().sendString("echo message");
                assertThat(webSocketListener.textMessages.poll(10, TimeUnit.SECONDS), is("echo message"));
                session.close();
                assertTrue(webSocketListener.closeLatch.await(10, TimeUnit.SECONDS));
                assertThat(webSocketListener.closeCode, is(StatusCode.NORMAL));

                // Verify /test2 is able to establish a WebSocket connection.
                webSocketListener = new WsListener();
                session = wsClient.connect(webSocketListener, serverUri.resolve("/test2")).get(10, TimeUnit.SECONDS);
                session.getRemote().sendString("echo message");
                assertThat(webSocketListener.textMessages.poll(10, TimeUnit.SECONDS), is("echo message"));
                session.close();
                assertTrue(webSocketListener.closeLatch.await(10, TimeUnit.SECONDS));
                assertThat(webSocketListener.closeCode, is(StatusCode.NORMAL));
            }
        }
    }

    public static class WsListener implements WebSocketListener
    {
        public BlockingArrayQueue<String> textMessages = new BlockingArrayQueue<>();
        public final CountDownLatch closeLatch = new CountDownLatch(1);
        public int closeCode;

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            this.closeCode = statusCode;
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketText(String message)
        {
            textMessages.add(message);
        }
    }

    @Test
    public void testStartStopLog4j2Modules() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();

        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args = {
            "--approve-all-licenses",
            "--add-modules=http,logging-log4j2"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2.xml"),
                Paths.get(jettyBase.toString(), "resources").resolve("log4j2.xml"),
                StandardCopyOption.REPLACE_EXISTING);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitLogsFileFor(
                    jettyBase.resolve("logs").resolve("jetty.log"),
                    "Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testJavaUtilLogging() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args = {
            "--approve-all-licenses",
            "--add-modules=http,logging-jul"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path julConfig = run1.getConfig().getJettyBase().resolve("resources/java-util-logging.properties");
            assertTrue(Files.exists(julConfig));
            Files.write(julConfig, Arrays.asList(System.lineSeparator(), "org.eclipse.jetty.level=FINE"), StandardOpenOption.APPEND);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertThat(run2.getLogs().stream()
                    // Check that the level formatting is that of the j.u.l. configuration file.
                    .filter(log -> log.contains("[FINE]"))
                    .count(), greaterThan(0L));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @Test
    public void testJavaUtilLoggingBridge() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args = {
            "--approve-all-licenses",
            "--add-modules=http,logging-jul-capture"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyBase = run1.getConfig().getJettyBase();

            Path julConfig = jettyBase.resolve("resources/java-util-logging.properties");
            assertTrue(Files.exists(julConfig));

            Path etc = jettyBase.resolve("etc");
            Files.createDirectories(etc);
            Path julXML = etc.resolve("jul.xml");
            String loggerName = getClass().getName();
            String message = "test-log-line";
            String xml = "" +
                "<?xml version=\"1.0\"?>" +
                "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://www.eclipse.org/jetty/configure_10_0.dtd\">" +
                "<Configure>" +
                "  <Call name=\"getLogger\" class=\"java.util.logging.Logger\">" +
                "    <Arg>" + loggerName + "</Arg>" +
                "    <Call name=\"log\">" +
                "      <Arg><Get class=\"java.util.logging.Level\" name=\"FINE\" /></Arg>" +
                "      <Arg>" + message + "</Arg>" +
                "    </Call>" +
                "  </Call>" +
                "</Configure>";
            Files.write(julXML, List.of(xml), StandardOpenOption.CREATE);

            Path julIni = jettyBase.resolve("start.d/logging-jul-capture.ini");
            assertTrue(Files.exists(julIni));
            Files.write(julIni, List.of("etc/jul.xml"), StandardOpenOption.APPEND);

            Path jettyLogConfig = jettyBase.resolve("resources/jetty-logging.properties");
            Files.write(jettyLogConfig, List.of(loggerName + ".LEVEL=DEBUG"), StandardOpenOption.TRUNCATE_EXISTING);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertEquals(1, run2.getLogs().stream()
                    // Check that the level formatting is that of the j.u.l. configuration file.
                    .filter(log -> log.contains(message))
                    .count());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @Test
    public void testBeforeDirectiveInModule() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--add-modules=https,test-keystore"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyBase = run1.getConfig().getJettyBase();

            Path jettyBaseEtc = jettyBase.resolve("etc");
            Files.createDirectories(jettyBaseEtc);
            Path sslPatchXML = jettyBaseEtc.resolve("ssl-patch.xml");
            String nextProtocol = "fcgi/1.0";
            String xml = "" +
                "<?xml version=\"1.0\"?>" +
                "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://www.eclipse.org/jetty/configure_10_0.dtd\">" +
                "<Configure id=\"sslConnector\" class=\"org.eclipse.jetty.server.ServerConnector\">" +
                "  <Call name=\"addIfAbsentConnectionFactory\">" +
                "    <Arg>" +
                "      <New class=\"org.eclipse.jetty.server.SslConnectionFactory\">" +
                "        <Arg name=\"next\">" + nextProtocol + "</Arg>" +
                "        <Arg name=\"sslContextFactory\"><Ref refid=\"sslContextFactory\"/></Arg>" +
                "      </New>" +
                "    </Arg>" +
                "  </Call>" +
                "  <Call name=\"addConnectionFactory\">" +
                "    <Arg>" +
                "      <New class=\"org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory\">" +
                "        <Arg><Ref refid=\"sslHttpConfig\" /></Arg>" +
                "      </New>" +
                "    </Arg>" +
                "  </Call>" +
                "</Configure>";
            Files.write(sslPatchXML, List.of(xml), StandardOpenOption.CREATE);

            Path jettyBaseModules = jettyBase.resolve("modules");
            Files.createDirectories(jettyBaseModules);
            Path sslPatchModule = jettyBaseModules.resolve("ssl-patch.mod");
            String module = "" +
                "[depends]\n" +
                "fcgi\n" +
                "\n" +
                "[before]\n" +
                "https\n" +
                "http2\n" + // http2 is not explicitly enabled.
                "\n" +
                "[after]\n" +
                "ssl\n" +
                "\n" +
                "[xml]\n" +
                "etc/ssl-patch.xml\n";
            Files.write(sslPatchModule, List.of(module), StandardOpenOption.CREATE);

            String[] args2 = {
                "--add-modules=ssl-patch"
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitFor(10, TimeUnit.SECONDS));
                assertEquals(0, run2.getExitValue());

                int port = distribution.freePort();
                try (JettyHomeTester.Run run3 = distribution.start("jetty.http.port=" + port))
                {
                    assertTrue(run3.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                    // Check for the protocol order: fcgi must be after ssl and before http.
                    assertTrue(run3.getLogs().stream()
                        .anyMatch(log -> log.contains("(ssl, fcgi/1.0, http/1.1)")));

                    // Protocol "h2" must not be enabled because the
                    // http2 Jetty module was not explicitly enabled.
                    assertFalse(run3.getLogs().stream()
                        .anyMatch(log -> log.contains("h2")), "Full logs: " + String.join("", run3.getLogs()));
                }
            }
        }
    }

    @Test
    public void testDefaultLoggingProviderNotActiveWhenExplicitProviderIsPresent() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution1 = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=logging-logback,http"
        };

        try (JettyHomeTester.Run run1 = distribution1.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyBase = run1.getConfig().getJettyBase();

            assertTrue(Files.exists(jettyBase.resolve("resources/logback.xml")));
            // The jetty-logging.properties should be absent.
            assertFalse(Files.exists(jettyBase.resolve("resources/jetty-logging.properties")));
        }

        JettyHomeTester distribution2 = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        // Try the modules in reverse order, since it may execute a different code path.
        String[] args2 = {
            "--approve-all-licenses",
            "--add-modules=http,logging-logback"
        };

        try (JettyHomeTester.Run run2 = distribution2.start(args2))
        {
            assertTrue(run2.awaitFor(1000, TimeUnit.SECONDS));
            assertEquals(0, run2.getExitValue());

            Path jettyBase = run2.getConfig().getJettyBase();

            assertTrue(Files.exists(jettyBase.resolve("resources/logback.xml")));
            // The jetty-logging.properties should be absent.
            assertFalse(Files.exists(jettyBase.resolve("resources/jetty-logging.properties")));
        }
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_16)
    public void testUnixDomain() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=unixdomain-http"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int maxUnixDomainPathLength = 108;
            Path path = Files.createTempFile("unix", ".sock");
            if (path.normalize().toAbsolutePath().toString().length() > maxUnixDomainPathLength)
                path = Files.createTempFile(Path.of("/tmp"), "unix", ".sock");
            assertTrue(Files.deleteIfExists(path));
            try (JettyHomeTester.Run run2 = distribution.start("jetty.unixdomain.path=" + path))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                ClientConnector connector = ClientConnector.forUnixDomain(path);
                client = new HttpClient(new HttpClientTransportDynamic(connector));
                client.start();
                ContentResponse response = client.GET("http://localhost/path");
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @Test
    public void testModuleWithExecEmitsWarning() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        Path jettyBase = distribution.getJettyBase();
        Path jettyBaseModules = jettyBase.resolve("modules");
        Files.createDirectories(jettyBaseModules);
        Path execModule = jettyBaseModules.resolve("exec.mod");
        String module = "" +
            "[exec]\n" +
            "--show-version";
        Files.write(execModule, List.of(module), StandardOpenOption.CREATE);

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=http,exec")))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertTrue(run2.getLogs().stream()
                    .anyMatch(log -> log.contains("WARN") && log.contains("Forking")));
            }
        }
    }

    @Test
    public void testIniSectionPropertyOverriddenByCommandLine() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        Path jettyBase = distribution.getJettyBase();
        Path jettyBaseModules = jettyBase.resolve("modules");
        Files.createDirectories(jettyBaseModules);
        String pathProperty = "jetty.sslContext.keyStorePath";
        // Create module with an [ini] section with an invalid password,
        // which should be overridden on the command line at startup.
        String module = "" +
            "[depends]\n" +
            "ssl\n" +
            "\n" +
            "[ini]\n" +
            "" + pathProperty + "=modbased\n";
        String moduleName = "ssl-ini";
        Files.write(jettyBaseModules.resolve(moduleName + ".mod"), module.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);

        try (JettyHomeTester.Run run1 = distribution.start("--add-module=https,test-keystore,ssl-ini"))
        {
            assertTrue(run1.awaitFor(20, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Override the property on the command line with the correct password.
            try (JettyHomeTester.Run run2 = distribution.start(pathProperty + "=cmdline"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));
                assertTrue(Files.exists(jettyBase.resolve("cmdline")));
                assertFalse(Files.exists(jettyBase.resolve("modbased")));
            }
        }
    }

    @Test
    public void testWellKnownModule() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();
        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=http,well-known"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Ensure .well-known directory exists.
            Path wellKnown = distribution.getJettyBase().resolve(".well-known");
            assertTrue(FS.exists(wellKnown));

            // Write content to a file in the .well-known directory.
            String testFileContent = "hello world " + UUID.randomUUID();
            File testFile = wellKnown.resolve("testFile").toFile();
            assertTrue(testFile.createNewFile());
            testFile.deleteOnExit();
            FileWriter fileWriter = new FileWriter(testFile);
            fileWriter.write(testFileContent);
            fileWriter.close();

            int port = distribution.freePort();
            String[] args2 = {
                "jetty.http.port=" + port
                //"jetty.server.dumpAfterStart=true"
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                // Test we can access the file in the .well-known directory.
                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/.well-known/testFile");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @Test
    public void testDeprecatedModule() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        Path jettyBase = distribution.getJettyBase();
        Path jettyBaseModules = jettyBase.resolve("modules");
        Files.createDirectories(jettyBaseModules);
        Path deprecatedModule = jettyBaseModules.resolve("deprecated.mod");
        String description = "A deprecated module.";
        String reason = "This module is deprecated.";
        List<String> lines = List.of(
            "[description]",
            description,
            "[deprecated]",
            reason,
            "[tags]",
            "deprecated"
        );
        Files.write(deprecatedModule, lines, StandardOpenOption.CREATE);

        try (JettyHomeTester.Run listConfigRun = distribution.start(List.of("--list-modules=deprecated")))
        {
            assertTrue(listConfigRun.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, listConfigRun.getExitValue());

            assertTrue(listConfigRun.getLogs().stream().anyMatch(log -> log.contains(description)));
        }

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=http,deprecated")))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            assertTrue(run1.getLogs().stream().anyMatch(log -> log.contains("WARN") && log.contains(reason)));

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertTrue(run2.getLogs().stream()
                    .anyMatch(log -> log.contains("WARN") && log.contains(reason)));
            }
        }
    }

    @Test
    public void testH3() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http3,test-keystore"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int h2Port = distribution.freePort();
            int h3Port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(List.of("jetty.ssl.selectors=1", "jetty.ssl.port=" + h2Port, "jetty.quic.port=" + h3Port)))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                HTTP3Client http3Client = new HTTP3Client();
                http3Client.getQuicConfiguration().setVerifyPeerCertificates(false);
                this.client = new HttpClient(new HttpClientTransportOverHTTP3(http3Client));
                this.client.start();
                ContentResponse response = this.client.newRequest("localhost", h3Port)
                    .scheme(HttpScheme.HTTPS.asString())
                    .path("/path")
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @Test
    public void testOpenID() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=http,webapp,deploy,openid"
        };

        String clientId = "clientId123";
        String clientSecret = "clientSecret456";
        OpenIdProvider openIdProvider = new OpenIdProvider(clientId, clientSecret);
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty.tests:test-openid-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test");

            int port = distribution.freePort();
            openIdProvider.addRedirectUri("http://localhost:" + port + "/test/j_security_check");
            openIdProvider.start();
            String[] args2 = {
                "jetty.http.port=" + port,
                "jetty.ssl.port=" + port,
                "jetty.openid.provider=" + openIdProvider.getProvider(),
                "jetty.openid.clientId=" + clientId,
                "jetty.openid.clientSecret=" + clientSecret,
                //"jetty.server.dumpAfterStart=true",
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                startHttpClient(false);
                String uri = "http://localhost:" + port + "/test";
                openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

                // Initially not authenticated
                ContentResponse response = client.GET(uri + "/");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                String content = response.getContentAsString();
                assertThat(content, containsString("not authenticated"));

                // Request to login is success
                response = client.GET(uri + "/login");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                content = response.getContentAsString();
                assertThat(content, containsString("success"));

                // Now authenticated we can get info
                response = client.GET(uri + "/");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                content = response.getContentAsString();
                assertThat(content, containsString("userId: 123456789"));
                assertThat(content, containsString("name: Alice"));
                assertThat(content, containsString("email: Alice@example.com"));

                // Request to admin page gives 403 as we do not have admin role
                response = client.GET(uri + "/admin");
                assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));

                // We are no longer authenticated after logging out
                response = client.GET(uri + "/logout");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                content = response.getContentAsString();
                assertThat(content, containsString("not authenticated"));

            }
        }
        finally
        {
            openIdProvider.stop();
        }
    }

    @Test
    public void testDryRunProperties() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {"--add-to-start=server,logging-jetty"};
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            String[] args2 = {"--dry-run"};
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                run2.awaitFor(5, TimeUnit.SECONDS);
                Queue<String> logs = run2.getLogs();
                assertThat(logs.size(), equalTo(1));
                assertThat(logs.poll(), not(containsString("${jetty.home.uri}")));
            }
        }
    }
}
