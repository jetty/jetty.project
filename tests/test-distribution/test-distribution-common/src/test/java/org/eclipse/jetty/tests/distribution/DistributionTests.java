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

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.toolchain.test.PathMatchers;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testQuickStartGenerationAndRun(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            "resources", "server", "http",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env),
            toEnvironment("servlet", env),
            toEnvironment("quickstart", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
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
                    assertTrue(run3.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                    startHttpClient();
                    ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("JSP Examples"));
                    assertThat(response.getContentAsString(), not(containsString("<%")));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testSimpleWebAppWithJSPandJSTL(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            "resources", "server", "http", "jmx",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env),
            toEnvironment("glassfish-jstl", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--create-start-ini", "--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Verify that --create-start-ini works
            assertTrue(Files.exists(distribution.getJettyBase().resolve("start.ini")));

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/jstl.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee10"})
    public void testSimpleWebAppWithJSPOnModulePath(String env) throws Exception
    {
        // Testing with env=ee9 is not possible because jakarta.transaction:1.x
        // does not have a proper module-info.java, so JPMS resolution will fail.
        // For env=ee10, jakarta.transaction:2.x has a proper module-info.java.

        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            "resources", "server", "http", "jmx",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testSimpleWebAppWithJSPOverH2C(String env) throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(env, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testSimpleWebAppWithJSPOverH2(String env) throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(env, true);
    }

    private void testSimpleWebAppWithJSPOverHTTP2(String env, boolean ssl) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            (ssl ? "http2,test-keystore" : "http2c"),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            String portProp = ssl ? "jetty.ssl.port" : "jetty.http.port";
            try (JettyHomeTester.Run run2 = distribution.start(portProp + "=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

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

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testLog4j2ModuleWithSimpleWebAppWithJSP(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            "resources", "server", "http", "logging-log4j2",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env),
            toEnvironment("servlet", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());
            assertTrue(Files.exists(distribution.getJettyBase().resolve("resources/log4j2.xml")));

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
                assertTrue(Files.exists(distribution.getJettyBase().resolve("resources/log4j2.xml")));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"http,ee9", "https,ee9", "http,ee10", "https,ee10"})
    public void testWebsocketClientInWebappProvidedByServer(String scheme, String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        boolean ssl = "https".equals(scheme);
        String mods = String.join(",",
            "resources", "server", "jmx",
            ssl ? "https,test-keystore" : "http",
            toEnvironment("webapp", env),
            toEnvironment("websocket-jakarta", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env),
            toEnvironment("websocket-jetty-client", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-client-provided-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(ssl ? "jetty.ssl.port=" + port : "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                // We should get the correct configuration from the jetty-websocket-httpclient.xml file.
                startHttpClient(ssl);
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
    @CsvSource({"http,ee9", "https,ee9", "http,ee10", "https,ee10"})
    public void testWebsocketClientInWebapp(String scheme, String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        boolean ssl = "https".equals(scheme);
        String mods = String.join(",",
            "resources", "server", "jmx",
            ssl ? "https,test-keystore" : "http",
            toEnvironment("webapp", env),
            toEnvironment("websocket-jakarta", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-client-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(ssl ? "jetty.ssl.port=" + port : "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

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

        String downloadURI = "https://repo1.maven.org/maven2/org/eclipse/jetty/maven-metadata.xml";
        String outPath = "etc/maven-metadata.xml";
        try (JettyHomeTester.Run run = distribution.start("--download=" + downloadURI + "|" + outPath))
        {
            assertTrue(run.awaitConsoleLogsFor("Base directory was modified", 110, TimeUnit.SECONDS));
            Path target = jettyBase.resolve(outPath);
            assertTrue(Files.exists(target), "could not create " + target);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testWebAppWithProxyAndJPMS(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            "resources", "http",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-proxy-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "proxy");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port, "jetty.server.dumpAfterStart=true"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP(1)));
                ContentResponse response = client.GET("http://localhost:" + port + "/proxy/current/");
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"ee9,false", "ee10,false", "ee10,true"})
    public void testSimpleWebAppWithWebsocket(String env, String jpms) throws Exception
    {
        // Testing ee9 with JPMS won't work because ee9 jakarta.* jars
        // do not have a proper module-info.java so JPMS resolution fails.

        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String mods = String.join(",",
            "resources", "http", "jmx",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("websocket-jakarta", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test1");
            distribution.installWarFile(webApp, "test2");

            int port = distribution.freePort();
            List<String> args2 = new ArrayList<>();
            args2.add("jetty.http.port=" + port);
            if (Boolean.parseBoolean(jpms))
                args2.add("--jpms");
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                URI serverUri = URI.create("ws://localhost:" + port + "/test1/");
                WsListener webSocketListener = new WsListener();
                var wsBuilder = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder();
                WebSocket webSocket = wsBuilder.buildAsync(serverUri, webSocketListener).get(10, TimeUnit.SECONDS);

                // Verify /test1 is able to establish a WebSocket connection.
                webSocket.sendText("echo message", true);
                assertThat(webSocketListener.textMessages.poll(10, TimeUnit.SECONDS), is("echo message"));
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
                assertTrue(webSocketListener.closeLatch.await(10, TimeUnit.SECONDS));
                assertThat(webSocketListener.closeCode, is(WebSocket.NORMAL_CLOSURE));

                // Verify /test2 is able to establish a WebSocket connection.
                serverUri = URI.create("ws://localhost:" + port + "/test2/");
                webSocketListener = new WsListener();
                webSocket = wsBuilder.buildAsync(serverUri, webSocketListener).get(10, TimeUnit.SECONDS);
                webSocket.sendText("echo message", true);
                assertThat(webSocketListener.textMessages.poll(10, TimeUnit.SECONDS), is("echo message"));
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
                assertTrue(webSocketListener.closeLatch.await(10, TimeUnit.SECONDS));
                assertThat(webSocketListener.closeCode, is(WebSocket.NORMAL_CLOSURE));
            }
        }
    }

    public static class WsListener implements WebSocket.Listener
    {
        public BlockingArrayQueue<String> textMessages = new BlockingArrayQueue<>();
        public final CountDownLatch closeLatch = new CountDownLatch(1);
        public int closeCode;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            textMessages.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            closeCode = statusCode;
            closeLatch.countDown();
            return null;
        }
    }

    @Test
    public void testStartStopLog4j2Modules() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,logging-log4j2"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2.xml"),
                distribution.getJettyBase().resolve("resources").resolve("log4j2.xml"),
                StandardCopyOption.REPLACE_EXISTING);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitLogsFileFor(
                    distribution.getJettyBase().resolve("logs").resolve("jetty.log"),
                    "Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
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

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,logging-jul"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path julConfig = run1.getConfig().getJettyBase().resolve("resources/java-util-logging.properties");
            assertTrue(Files.exists(julConfig));
            Files.write(julConfig, Arrays.asList(System.lineSeparator(), "org.eclipse.jetty.level=FINE"), StandardOpenOption.APPEND);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));
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

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,logging-jul-capture"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
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
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));
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

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=https,test-keystore"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyBase = run1.getConfig().getJettyBase();

            Path jettyBaseEtc = jettyBase.resolve("etc");
            Files.createDirectories(jettyBaseEtc);
            Path sslPatchXML = jettyBaseEtc.resolve("ssl-patch.xml");
            String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
                <Configure id="sslConnector" class="org.eclipse.jetty.server.ServerConnector">
                  <Call name="addIfAbsentConnectionFactory">
                    <Arg>
                      <New class="org.eclipse.jetty.server.SslConnectionFactory">
                        <Arg name="next">fcgi/1.0</Arg>
                        <Arg name="sslContextFactory"><Ref refid="sslContextFactory"/></Arg>
                      </New>
                    </Arg>
                  </Call>
                  <Call name="addConnectionFactory">
                    <Arg>
                      <New class="org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory">
                        <Arg><Ref refid="sslHttpConfig" /></Arg>
                      </New>
                    </Arg>
                  </Call>
                </Configure>
                """;
            Files.write(sslPatchXML, List.of(xml), StandardOpenOption.CREATE);

            Path jettyBaseModules = jettyBase.resolve("modules");
            Files.createDirectories(jettyBaseModules);
            Path sslPatchModule = jettyBaseModules.resolve("ssl-patch.mod");
            // http2 is not explicitly enabled.
            String module = """
                [depends]
                fcgi

                [before]
                https
                http2

                [after]
                ssl

                [xml]
                etc/ssl-patch.xml
                """;
            Files.write(sslPatchModule, List.of(module), StandardOpenOption.CREATE);

            try (JettyHomeTester.Run run2 = distribution.start("--add-modules=ssl-patch"))
            {
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
                assertEquals(0, run2.getExitValue());

                int port = distribution.freePort();
                try (JettyHomeTester.Run run3 = distribution.start("jetty.http.port=" + port))
                {
                    assertTrue(run3.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

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

        try (JettyHomeTester.Run run1 = distribution1.start("--approve-all-licenses", "--add-modules=logging-logback,http"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
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
        try (JettyHomeTester.Run run2 = distribution2.start("--approve-all-licenses", "--add-modules=http,logging-logback"))
        {
            assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
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
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int maxUnixDomainPathLength = 108;
            Path path = Files.createTempFile("unix", ".sock");
            if (path.normalize().toAbsolutePath().toString().length() > maxUnixDomainPathLength)
                path = Files.createTempFile(Path.of("/tmp"), "unix", ".sock");
            assertTrue(Files.deleteIfExists(path));
            try (JettyHomeTester.Run run2 = distribution.start("jetty.unixdomain.path=" + path))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

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
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));
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
        Files.writeString(jettyBaseModules.resolve(moduleName + ".mod"), module, StandardOpenOption.CREATE);

        try (JettyHomeTester.Run run1 = distribution.start("--add-module=https,test-keystore,ssl-ini"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Override the property on the command line with the correct password.
            try (JettyHomeTester.Run run2 = distribution.start(pathProperty + "=cmdline"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                assertThat("${jetty.base}/cmdline", jettyBase.resolve("cmdline"), PathMatchers.isRegularFile());
                assertThat("${jetty.base}/modbased", jettyBase.resolve("modbased"), not(PathMatchers.exists()));
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

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,well-known"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Ensure .well-known directory exists.
            Path wellKnown = distribution.getJettyBase().resolve(".well-known");
            assertTrue(Files.exists(wellKnown));

            // Write content to a file in the .well-known directory.
            String testFileContent = "hello world " + UUID.randomUUID();
            File testFile = wellKnown.resolve("testFile").toFile();
            assertTrue(testFile.createNewFile());
            testFile.deleteOnExit();
            try (FileWriter fileWriter = new FileWriter(testFile))
            {
                fileWriter.write(testFileContent);
            }

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

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
            assertTrue(listConfigRun.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, listConfigRun.getExitValue());

            assertTrue(listConfigRun.getLogs().stream().anyMatch(log -> log.contains(description)));
        }

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=http,deprecated")))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            assertTrue(run1.getLogs().stream().anyMatch(log -> log.contains("WARN") && log.contains(reason)));

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));
                assertTrue(run2.getLogs().stream()
                    .anyMatch(log -> log.contains("WARN") && log.contains(reason)));
            }
        }
    }

    @Test
    @DisabledIfSystemProperty(named = "env", matches = "ci")
    public void testH3() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http3,test-keystore"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int h2Port = distribution.freePort();
            int h3Port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(List.of("jetty.ssl.selectors=1", "jetty.ssl.port=" + h2Port, "jetty.quic.port=" + h3Port)))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

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
    public void testDryRunProperties() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-to-start=server,logging-jetty"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            try (JettyHomeTester.Run run2 = distribution.start("--dry-run"))
            {
                run2.awaitFor(5, TimeUnit.SECONDS);
                Queue<String> logs = run2.getLogs();
                assertThat(logs.size(), equalTo(1));
                assertThat(logs.poll(), not(containsString("${jetty.home.uri}")));
            }
        }
    }

    @Test
    public void testFastCGIProxying() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,fcgi,fcgi-proxy,core-deploy")))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Add a FastCGI connector to simulate, for example, php-fpm.
            int fcgiPort = distribution.freePort();
            Path jettyBase = distribution.getJettyBase();
            Path jettyBaseEtc = jettyBase.resolve("etc");
            Files.createDirectories(jettyBaseEtc);
            Path fcgiConnectorXML = jettyBaseEtc.resolve("fcgi-connector.xml");
            Files.writeString(fcgiConnectorXML, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
                <Configure id="Server">
                  <Call name="addConnector">
                    <Arg>
                      <New id="fcgiConnector" class="org.eclipse.jetty.server.ServerConnector">
                        <Arg><Ref refid="Server" /></Arg>
                        <Arg type="int">1</Arg>
                        <Arg type="int">1</Arg>
                        <Arg>
                          <Array type="org.eclipse.jetty.server.ConnectionFactory">
                            <Item>
                              <New class="org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory">
                                <Arg><Ref refid="httpConfig" /></Arg>
                              </New>
                            </Item>
                          </Array>
                        </Arg>
                        <Set name="port">$P</Set>
                      </New>
                    </Arg>
                  </Call>
                </Configure>
                """.replace("$P", String.valueOf(fcgiPort)), StandardOpenOption.CREATE);

            // Deploy a Jetty context XML file that is only necessary for the test,
            // as it simulates, for example, what the php-fpm server would return.
            Path jettyBaseWork = jettyBase.resolve("work");
            Path phpXML = jettyBase.resolve("webapps").resolve("php.xml");
            Files.writeString(phpXML, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
                <Configure class="org.eclipse.jetty.server.handler.ContextHandler">
                  <Set name="contextPath">/php</Set>
                  <Set name="baseResourceAsPath">
                    <Call class="java.nio.file.Path" name="of">
                      <Arg>$R</Arg>
                    </Call>
                  </Set>
                  <Set name="handler">
                    <New class="org.eclipse.jetty.server.handler.ResourceHandler" />
                  </Set>
                </Configure>
                """.replace("$R", jettyBaseWork.toAbsolutePath().toString()), StandardOpenOption.CREATE);
            // Save a file in $JETTY_BASE/work so that it can be requested.
            String testFileContent = "hello";
            Files.writeString(jettyBaseWork.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);

            // Deploy a Jetty context XML file that sets up the FastCGIProxyHandler.
            // Converts URIs from http://host:<httpPort>/proxy/foo to http://host:<fcgiPort>/app/foo.
            Path proxyXML = jettyBase.resolve("webapps").resolve("proxy.xml");
            Files.writeString(proxyXML, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
                <Configure class="org.eclipse.jetty.server.handler.ContextHandler">
                  <Set name="contextPath">/proxy</Set>
                  <Set name="handler">
                    <New class="org.eclipse.jetty.fcgi.proxy.FastCGIProxyHandler">
                      <Arg>(https?)://([^:]+):(\\d+)/([^/]+)/(.*)</Arg>
                      <Arg>$1://$2:$P/php/$5</Arg>
                      <Arg>/var/wordpress</Arg>
                    </New>
                  </Set>
                </Configure>
                """.replace("$P", String.valueOf(fcgiPort)), StandardOpenOption.CREATE);

            int httpPort = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort, "etc/fcgi-connector.xml"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                // Make a request to the /proxy context on the httpPort; it should be converted to FastCGI
                // and reverse proxied to the simulated php-fpm /php context on the fcgiPort.
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/proxy/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }
}
