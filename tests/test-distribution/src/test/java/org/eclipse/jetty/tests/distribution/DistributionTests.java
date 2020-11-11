//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.io.BufferedWriter;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.unixsocket.server.UnixSocketConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
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
                    assertThat(response.getContentAsString(), containsString("Hello"));
                    assertThat(response.getContentAsString(), not(containsString("<%")));
                }
            }
        }
    }

    @Test
    public void testSimpleWebAppWithJSP() throws Exception
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
            "--add-modules=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets"
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Verify that --create-start-ini works
            assertTrue(Files.exists(jettyBase.resolve("start.ini")));

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
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
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testSimpleWebAppWithJSPOverH2C() throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(false);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
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
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)  // jnr not supported on windows
    public void testUnixSocket() throws Exception
    {
        Path tmpSockFile;
        String unixSocketTmp = System.getProperty("unix.socket.tmp");
        if (StringUtil.isNotBlank(unixSocketTmp))
            tmpSockFile = Files.createTempFile(Paths.get(unixSocketTmp), "unix", ".sock");
        else
            tmpSockFile = Files.createTempFile("unix", ".sock");
        if (tmpSockFile.toAbsolutePath().toString().length() > UnixSocketConnector.MAX_UNIX_SOCKET_PATH_LENGTH)
        {
            Path tmp = Paths.get("/tmp");
            assumeTrue(Files.exists(tmp) && Files.isDirectory(tmp));
            tmpSockFile = Files.createTempFile(tmp, "unix", ".sock");
        }
        Path sockFile = tmpSockFile;
        assertTrue(Files.deleteIfExists(sockFile), "temp sock file cannot be deleted");

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

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            try (JettyHomeTester.Run run2 = distribution.start("jetty.unixsocket.path=" + sockFile.toString()))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverUnixSockets(sockFile.toString())));
                ContentResponse response = client.GET("http://localhost/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());
            assertTrue(Files.exists(jettyBase.resolve("resources/log4j2.xml")));

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
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
     * This reproduces some classloading issue with MethodHandles in JDK14-15, this has been fixed in JDK16.
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
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
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
                Session session = wsClient.connect(webSocketListener, serverUri.resolve("/test1")).get(5, TimeUnit.SECONDS);
                session.getRemote().sendString("echo message");
                assertThat(webSocketListener.textMessages.poll(5, TimeUnit.SECONDS), is("echo message"));
                session.close();
                assertTrue(webSocketListener.closeLatch.await(5, TimeUnit.SECONDS));
                assertThat(webSocketListener.closeCode, is(StatusCode.NORMAL));

                // Verify /test2 is able to establish a WebSocket connection.
                webSocketListener = new WsListener();
                session = wsClient.connect(webSocketListener, serverUri.resolve("/test2")).get(5, TimeUnit.SECONDS);
                session.getRemote().sendString("echo message");
                assertThat(webSocketListener.textMessages.poll(5, TimeUnit.SECONDS), is("echo message"));
                session.close();
                assertTrue(webSocketListener.closeLatch.await(5, TimeUnit.SECONDS));
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
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance() //
            .jettyVersion(jettyVersion) //
            .jettyBase(jettyBase) //
            .mavenLocalRepository(System.getProperty("mavenRepoPath")) //
            .build();

        String[] args = {
            "--approve-all-licenses",
            "--add-modules=http,logging-log4j2"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2.xml"), //
                Paths.get(jettyBase.toString(), "resources").resolve("log4j2.xml"), //
                StandardCopyOption.REPLACE_EXISTING);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitLogsFileFor(
                    jettyBase.resolve("logs").resolve("jetty.log"), //
                    "Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
