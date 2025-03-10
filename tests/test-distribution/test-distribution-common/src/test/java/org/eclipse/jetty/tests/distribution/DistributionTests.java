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

package org.eclipse.jetty.tests.distribution;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartByteRanges;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.PathMatchers;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistributionTests extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DistributionTests.class);

    @Test
    public void testStartStop() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testJettyConf() throws Exception
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

            Path pidfile = run1.getConfig().getJettyBase().resolve("jetty.pid");
            Path statefile = run1.getConfig().getJettyBase().resolve("jetty.state");

            int port = Tester.freePort();

            List<String> args = new ArrayList<>();
            args.add("jetty.http.port=" + port);
            args.add("jetty.state=" + statefile);
            args.add("jetty.pid=" + pidfile);

            Path confFile = run1.getConfig().getJettyHome().resolve("etc/jetty.conf");
            for (String line : Files.readAllLines(confFile, StandardCharsets.UTF_8))
            {
                if (line.startsWith("#") || StringUtil.isBlank(line))
                    continue; // skip
                args.add(line);
            }

            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                assertTrue(Files.isRegularFile(pidfile), "PID file should exist");
                assertTrue(Files.isRegularFile(statefile), "State file should exist");
                String state = tail(statefile);
                assertThat("State file", state, startsWith("STARTED "));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }

            await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(pidfile));
            await().atMost(Duration.ofSeconds(10)).until(() -> tail(statefile).startsWith("STOPPED "));
        }
    }

    /**
     * Get the last line of the file.
     *
     * @param file the file to read from
     * @return the string representing the last line of the file, or null if not found
     */
    private static String tail(Path file)
    {
        try
        {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty())
                return "";
            return lines.get(lines.size() - 1);
        }
        catch (IOException e)
        {
            return "";
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testQuickStartGenerationAndRun(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            try (JettyHomeTester.Run run2 = distribution.start("jetty.quickstart.mode=GENERATE"))
            {
                assertTrue(run2.awaitConsoleLogsFor("QuickStartGeneratorConfiguration:main: Generated", START_TIMEOUT, TimeUnit.SECONDS));
                Path unpackedWebapp = distribution.getJettyBase().resolve("webapps").resolve("test");
                assertTrue(Files.exists(unpackedWebapp));
                Path webInf = unpackedWebapp.resolve("WEB-INF");
                assertTrue(Files.exists(webInf));
                Path quickstartWebXml = webInf.resolve("quickstart-web.xml");
                assertTrue(Files.exists(quickstartWebXml));
                assertNotEquals(0, Files.size(quickstartWebXml));

                int port = Tester.freePort();

                try (JettyHomeTester.Run run3 = distribution.start("jetty.http.port=" + port, "jetty.quickstart.mode=QUICKSTART"))
                {
                    assertTrue(run3.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

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
    public void testSimpleWebAppWithJSPAndJSTL(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String mods = String.join(",",
            "resources", "server", "http", "jmx",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("glassfish-jstl", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));

                response = client.GET("http://localhost:" + port + "/test/jstl.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), not(containsString("<c:")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testSimpleWebAppWithJSPOverH2C(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        testSimpleWebAppWithJSPOverHTTP2(env, false, jettyBase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testSimpleWebAppWithJSPOverH2(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        testSimpleWebAppWithJSPOverHTTP2(env, true, jettyBase);
    }

    private void testSimpleWebAppWithJSPOverHTTP2(String env, boolean ssl, Path jettyBase) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            String portProp = ssl ? "jetty.ssl.port" : "jetty.http.port";
            try (JettyHomeTester.Run run2 = distribution.start(portProp + "=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

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
    @CsvSource({"http,ee9,false,", "http,ee9,true", "https,ee9,false", "http,ee10,false", "http,ee10,true", "https,ee10,false"})
    public void testWebsocketClientInWebappProvidedByServer(String scheme, String env, String jpms) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        boolean ssl = "https".equals(scheme);
        String mods = String.join(",",
            "resources", "server", "jmx",
            ssl ? "https,test-keystore" : "http",
            toEnvironment("webapp", env),
            toEnvironment("websocket-jakarta", env),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env),
            toEnvironment("websocket-jetty-client-webapp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-client-provided-webapp:war:" + jettyVersion);
            distribution.installWar(webApp, "test");

            int port = Tester.freePort();
            List<String> args = new ArrayList<>();
            args.add(ssl ? "jetty.ssl.port=" + port : "jetty.http.port=" + port);
            if (Boolean.parseBoolean(jpms))
                args.add("--jpms");
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                // We should get the correct configuration from the jetty-websocket-httpclient.xml file.
                startHttpClient(ssl);
                URI serverUri = URI.create(scheme + "://localhost:" + port + "/test");
                ContentResponse response = client.GET(serverUri);
                assertEquals(HttpStatus.OK_200, response.getStatus(), response.getContentAsString());
                String content = response.getContentAsString();
                assertThat(content, containsString("WebSocketEcho: success"));
                assertThat(content, containsString("ConnectTimeout: 4999"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"http,ee9,false", "http,ee9,true", "https,ee9,false", "http,ee10,false", "http,ee10,true", "https,ee10,false"})
    public void testWebsocketClientInWebapp(String scheme, String env, String jpms) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-client-webapp:war:" + jettyVersion);
            distribution.installWar(webApp, "test");

            int port = Tester.freePort();
            List<String> args = new ArrayList<>();
            args.add(ssl ? "jetty.ssl.port=" + port : "jetty.http.port=" + port);
            if (Boolean.parseBoolean(jpms))
                args.add("--jpms");
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                // We should get the correct configuration from the jetty-websocket-httpclient.xml file.
                startHttpClient(scheme.equals("https"));
                URI serverUri = URI.create(scheme + "://localhost:" + port + "/test");
                ContentResponse response = client.GET(serverUri);
                assertEquals(HttpStatus.OK_200, response.getStatus(), response.getContentAsString());
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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String downloadURI = "https://repo1.maven.org/maven2/org/eclipse/jetty/maven-metadata.xml";
        String outPath = "etc/maven-metadata.xml";
        try (JettyHomeTester.Run run = distribution.start("--download=" + downloadURI + "|" + outPath))
        {
            assertTrue(run.awaitConsoleLogsFor("Base directory was modified", 120, TimeUnit.SECONDS));
            Path target = jettyBase.resolve(outPath);
            assertTrue(Files.exists(target), "could not create " + target);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testWebAppWithProxyAndJPMS(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-proxy-webapp:war:" + jettyVersion);
            distribution.installWar(war, "proxy");

            Path loggingProps = distribution.getJettyBase().resolve("resources/jetty-logging.properties");

            String loggingConfig = """
                # Default for everything is INFO
                org.eclipse.jetty.LEVEL=INFO
                # to see full logger names
                # org.eclipse.jetty.logging.appender.NAME_CONDENSE=false
                # to see CR LF as-is (not escaped) in output (useful for DEBUG of request/response headers)
                org.eclipse.jetty.logging.appender.MESSAGE_ESCAPE=false
                # To enable DEBUG:oejepP.JavadocTransparentProxy
                org.eclipse.jetty.%s.proxy.ProxyServlet$Transparent.JavadocTransparentProxy.LEVEL=DEBUG
                """.formatted(env);

            Files.writeString(loggingProps, loggingConfig, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port, "jetty.server.dumpAfterStart=true"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP(1)));
                ContentResponse response = client.GET("http://localhost:" + port + "/proxy/jetty-12/index.html");
                assertEquals(HttpStatus.OK_200, response.getStatus(), () ->
                {
                    StringBuilder rawResponse = new StringBuilder();
                    rawResponse.append(response.getVersion()).append(' ');
                    rawResponse.append(response.getStatus()).append(' ');
                    rawResponse.append(response.getReason()).append('\n');
                    rawResponse.append(response.getHeaders());
                    rawResponse.append(response.getContentAsString());
                    return rawResponse.toString();
                });
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"ee9,false", "ee10,false", "ee10,true"})
    public void testSimpleWebAppWithWebsocket(String env, String jpms) throws Exception
    {
        // Testing ee9 with JPMS won't work because ee9 jakarta.* jars
        // do not have a proper module-info.java so JPMS resolution fails.
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-webapp:war:" + jettyVersion);
            distribution.installWar(webApp, "test1");
            distribution.installWar(webApp, "test2");

            int port = Tester.freePort();
            List<String> args2 = new ArrayList<>();
            args2.add("jetty.http.port=" + port);
            if (Boolean.parseBoolean(jpms))
                args2.add("--jpms");
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,logging-log4j2"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2.xml"),
                distribution.getJettyBase().resolve("resources").resolve("log4j2.xml"),
                StandardCopyOption.REPLACE_EXISTING);

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                Path logFile = distribution.getJettyBase().resolve("logs").resolve("jetty.log");
                await().atMost(10, TimeUnit.SECONDS).until(() -> Files.exists(logFile));
                await().atMost(10, TimeUnit.SECONDS).until(() ->
                {
                    try (Stream<String> lines = Files.lines(logFile))
                    {
                        return lines.anyMatch(line -> line.contains("Started oejs.Server@"));
                    }
                });

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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,logging-jul"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path julConfig = run1.getConfig().getJettyBase().resolve("resources/java-util-logging.properties");
            assertTrue(Files.exists(julConfig));
            Files.write(julConfig, Arrays.asList(System.lineSeparator(), "org.eclipse.jetty.level=FINE"), StandardOpenOption.APPEND);

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http,logging-jul-capture"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            //Path jettyBase = run1.getConfig().getJettyBase();

            Path julConfig = jettyBase.resolve("resources/java-util-logging.properties");
            assertTrue(Files.exists(julConfig));

            Path etc = jettyBase.resolve("etc");
            Files.createDirectories(etc);
            Path julXML = etc.resolve("jul.xml");
            String loggerName = getClass().getName();
            String message = "test-log-line";
            String xml = "" +
                         "<?xml version=\"1.0\"?>" +
                         "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://jetty.org/configure_10_0.dtd\">" +
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

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=https,test-keystore"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            //Path jettyBase = run1.getConfig().getJettyBase();

            Path jettyBaseEtc = jettyBase.resolve("etc");
            Files.createDirectories(jettyBaseEtc);
            Path sslPatchXML = jettyBaseEtc.resolve("ssl-patch.xml");
            String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
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
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS), String.join("", run2.getLogs()));
                assertEquals(0, run2.getExitValue());

                int port = Tester.freePort();
                int sslPort = Tester.freePort();
                try (JettyHomeTester.Run run3 = distribution.start("jetty.http.port=" + port, "jetty.ssl.port=" + sslPort))
                {
                    assertTrue(run3.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS),
                        String.join("", run3.getLogs()));

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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution1 = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution1.start("--approve-all-licenses", "--add-modules=logging-logback,http"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            //Path jettyBase = run1.getConfig().getJettyBase();

            assertTrue(Files.exists(jettyBase.resolve("resources/logback.xml")));
            // The jetty-logging.properties should be absent.
            assertFalse(Files.exists(jettyBase.resolve("resources/jetty-logging.properties")));
        }

        JettyHomeTester distribution2 = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        // Try the modules in reverse order, since it may execute a different code path.
        try (JettyHomeTester.Run run2 = distribution2.start("--approve-all-licenses", "--add-modules=http,logging-logback"))
        {
            assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run2.getExitValue());

            //Path jettyBase = run2.getConfig().getJettyBase();

            assertTrue(Files.exists(jettyBase.resolve("resources/logback.xml")));
            // The jetty-logging.properties should be absent.
            assertFalse(Files.exists(jettyBase.resolve("resources/jetty-logging.properties")));
        }
    }

    @Test
    public void testUnixDomain() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                ClientConnector connector = new ClientConnector();
                client = new HttpClient(new HttpClientTransportDynamic(connector, HttpClientConnectionFactory.HTTP11));
                client.start();
                ContentResponse response = client.newRequest("http://localhost/path")
                    .transport(new Transport.TCPUnix(path))
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @Test
    public void testModuleWithExecEmitsWarning() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

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

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                assertTrue(run2.getLogs().stream()
                    .anyMatch(log -> log.contains("WARN") && log.contains("Forking")));
            }
        }
    }

    @Test
    public void testIniSectionPropertyOverriddenByCommandLine() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

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
        Files.writeString(jettyBaseModules.resolve("ssl-ini.mod"), module, StandardOpenOption.CREATE);

        try (JettyHomeTester.Run run1 = distribution.start("--add-module=https,test-keystore,ssl-ini"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Override the property on the command line with the correct password.
            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(pathProperty + "=cmdline", "jetty.ssl.port=" + port))
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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
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

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

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

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                assertTrue(run2.getLogs().stream()
                    .anyMatch(log -> log.contains("WARN") && log.contains(reason)));
            }
        }
    }

    @Test
    @Tag("flaky")
    public void testH3() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=http3,test-keystore"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int h2Port = Tester.freePort();
            int h3Port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(List.of("jetty.ssl.selectors=1", "jetty.ssl.port=" + h2Port, "jetty.quic.port=" + h3Port)))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
                HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, null));
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
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-to-start=server,logging-jetty"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            try (JettyHomeTester.Run run2 = distribution.start("--dry-run"))
            {
                run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS);
                Collection<String> logs = run2.getLogs();
                assertThat(logs.size(), equalTo(1));
                assertThat(logs.iterator().next(), not(containsString("${jetty.home.uri}")));
            }
        }
    }

    @Test
    public void testRequestLogFormatWithSpaces() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] args1 = {"--add-module=server,http,deploy,requestlog"};
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Setup custom format string with spaces
            Path requestLogIni = distribution.getJettyBase().resolve("start.d/requestlog.ini");
            List<String> lines = List.of(
                "--module=requestlog",
                "jetty.requestlog.filePath=logs/test.request.log",
                "jetty.requestlog.formatString=%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t [foo space here] \"%r\" %s %O \"%{Referer}i\" \"%{User-Agent}i\""
            );
            Files.write(requestLogIni, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

            int port = Tester.freePort();
            String[] args2 = {
                "jetty.http.port=" + port,
                };
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient(false);

                String uri = "http://localhost:" + port + "/test";

                // Generate a request
                ContentResponse response = client.GET(uri + "/");
                // Don't really care about the result, as any request should be logged in the requestlog
                // We are just asserting a status here to ensure that the request is complete
                assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

                Path requestLog = distribution.getJettyBase().resolve("logs/test.request.log");
                List<String> loggedLines = Files.readAllLines(requestLog, StandardCharsets.UTF_8);
                for (String loggedLine : loggedLines)
                {
                    assertThat(loggedLine, containsString(" [foo space here] "));
                }
            }
        }
    }

    @Test
    public void testFastCGIProxying() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,fcgi,fcgi-proxy,core-deploy")))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Add a FastCGI connector to simulate, for example, php-fpm.
            int fcgiPort = Tester.freePort();
            //Path jettyBase = distribution.getJettyBase();
            Path jettyBaseEtc = jettyBase.resolve("etc");
            Files.createDirectories(jettyBaseEtc);
            Path fcgiConnectorXML = jettyBaseEtc.resolve("fcgi-connector.xml");
            Files.writeString(fcgiConnectorXML, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
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
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
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
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
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

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort, "etc/fcgi-connector.xml"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                // Make a request to the /proxy context on the httpPort; it should be converted to FastCGI
                // and reverse proxied to the simulated php-fpm /php context on the fcgiPort.
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/proxy/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testEEFastCGIProxying(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String mods = String.join(",",
            "resources", "http", "fcgi", "core-deploy",
            toEnvironment("deploy", env),
            toEnvironment("fcgi-proxy", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=" + mods)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=DEBUG
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);

            // Add a FastCGI connector to simulate, for example, php-fpm.
            int fcgiPort = Tester.freePort();
            Path jettyBaseEtc = jettyBase.resolve("etc");
            Files.createDirectories(jettyBaseEtc);
            Path fcgiConnectorXML = jettyBaseEtc.resolve("fcgi-connector.xml");
            Files.writeString(fcgiConnectorXML, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
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
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
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

            // Deploy a Jetty context XML file that sets up the FastCGIProxyServlet.
            // Converts URIs from http://host:<httpPort>/proxy/foo to http://host:<fcgiPort>/php/foo.
            Path proxyXML = jettyBase.resolve("webapps").resolve("proxy.xml");
            Files.writeString(proxyXML, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
                <Configure class="org.eclipse.jetty.$ENV.servlet.ServletContextHandler">
                  <Set name="contextPath">/proxy</Set>
                  <Call name="addServlet">
                    <Arg>org.eclipse.jetty.$ENV.fcgi.proxy.FastCGIProxyServlet</Arg>
                    <Arg>*.txt</Arg>
                    <Call name="setInitParameter">
                      <Arg>proxyTo</Arg>
                      <Arg>http://localhost:$P/php</Arg>
                    </Call>
                    <Call name="setInitParameter">
                      <Arg>scriptRoot</Arg>
                      <Arg>/var/wordpress</Arg>
                    </Call>
                  </Call>
                </Configure>
                """.replace("$ENV", env).replace("$P", String.valueOf(fcgiPort)), StandardOpenOption.CREATE);

            Path proxyProps = jettyBase.resolve("webapps").resolve("proxy.properties");
            Files.writeString(proxyProps, """
                environment=$ENV
                """.replace("$ENV", env), StandardOpenOption.CREATE);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort, "etc/fcgi-connector.xml"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                // Make a request to the /proxy context on the httpPort; it should be converted to FastCGI
                // and reverse proxied to the simulated php-fpm /php context on the fcgiPort.
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/proxy/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_19, max = JRE.JAVA_20)
    public void testVirtualThreadPoolPreview() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=threadpool-virtual-preview,http"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(List.of("jetty.http.selectors=1", "jetty.http.port=" + httpPort)))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.newRequest("localhost", httpPort)
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @DisabledForJreRange(max = JRE.JAVA_20)
    @ParameterizedTest
    @ValueSource(strings = {"threadpool-virtual", "threadpool-all-virtual"})
    public void testVirtualThreadPool(String threadPoolModule) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http," + threadPoolModule))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start(List.of("jetty.http.selectors=1", "jetty.http.port=" + httpPort)))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.newRequest("localhost", httpPort)
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testRangeRequestMultiPartRangeResponse(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String mods = String.join(",",
            "resources",
            "http",
            toEnvironment("deploy", env),
            toEnvironment("demo-simple", env)
        );

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=INFO
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);

            int httpPort = Tester.freePort();
            String contextPath = "/" + toEnvironment("demo-simple", env);
            try (JettyHomeTester.Run run2 = distribution.start(List.of("jetty.http.selectors=1", "jetty.http.port=" + httpPort)))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.newRequest("localhost", httpPort)
                    .path(contextPath + "/jetty.png")
                    // Use a range bigger than 4096, which is the default buffer size.
                    .headers(headers -> headers.put(HttpHeader.RANGE, "bytes=1-100,101-5000"))
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.PARTIAL_CONTENT_206, response.getStatus());
                String contentType = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
                assertThat(contentType, startsWith("multipart/byteranges"));
                String boundary = MultiPart.extractBoundary(contentType);
                Content.Source multiPartContent = new ByteBufferContentSource(ByteBuffer.wrap(response.getContent()));
                MultiPartByteRanges.Parts parts = new MultiPartByteRanges.Parser(boundary).parse(multiPartContent).get();
                assertThat(parts.size(), is(2));
                // Ranges are inclusive, so 1-100 is 100 bytes.
                assertThat(parts.get(0).getLength(), is(100L));
                assertThat(parts.get(1).getLength(), is(4900L));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee8", "ee9", "ee10"})
    public void testXmlDeployWarNotInWebapps(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        int httpPort = Tester.freePort();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("deploy", env) + "," + toEnvironment("webapp", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpPort
            };

            // Put war into ${jetty.base}/wars/ directory
            Path srcWar = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-simple-webapp:war:" + jettyVersion);
            Path warsDir = jettyBase.resolve("wars");
            FS.ensureDirExists(warsDir);
            Path destWar = warsDir.resolve("demo.war");
            Files.copy(srcWar, destWar);

            // Create XML for deployable
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
                                
                <Configure class="org.eclipse.jetty.%s.webapp.WebAppContext">
                  <Set name="contextPath">/demo</Set>
                  <Set name="war">%s</Set>
                </Configure>
                """.formatted(env, destWar.toString());
            Files.writeString(jettyBase.resolve("webapps/demo.xml"), xml, StandardCharsets.UTF_8);

            // Specify Environment Properties for this raw XML based deployable
            String props = """
                environment=%s
                """.formatted(env);
            Files.writeString(jettyBase.resolve("webapps/demo.properties"), props, StandardCharsets.UTF_8);

            /* The jetty.base tree should now look like this
             *
             * ${jetty.base}
             * ├── resources/
             * │   └── jetty-logging.properties
             * ├── start.d/
             * │   ├── ${env}-deploy.ini
             * │   ├── ${env}-webapp.ini
             * │   └── http.ini
             * ├── wars/
             * │   └── demo.war
             * ├── webapps/
             * │   ├── demo.properties
             * │   └── demo.xml
             * └── work/
             */

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/demo/index.html");
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @Test
    public void testInetAccessHandler() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=inetaccess,http"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int httpPort = Tester.freePort();
            List<String> args = List.of(
                "jetty.inetaccess.exclude=|/excludedPath/*",
                "jetty.http.port=" + httpPort);
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient();

                // Excluded path returns 403 response.
                ContentResponse response = client.newRequest("http://localhost:" + httpPort + "/excludedPath")
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

                // Other paths return 404 response.
                response = client.newRequest("http://localhost:" + httpPort + "/path")
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            }
        }
    }

    @Test
    public void testSendDateHeader() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http"))
        {
            assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int httpPort = Tester.freePort();
            List<String> args = List.of(
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.sendDateHeader=true"
            );
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient();

                List<String> hostHeaders = new ArrayList<>();
                hostHeaders.add("localhost");
                hostHeaders.add("127.0.0.1");
                try
                {
                    InetAddress localhost = InetAddress.getLocalHost();
                    hostHeaders.add(localhost.getHostName());
                    hostHeaders.add(localhost.getHostAddress());
                }
                catch (UnknownHostException e)
                {
                    LOG.debug("Unable to obtain InetAddress.LocalHost", e);
                }

                for (String hostHeader: hostHeaders)
                {
                    ContentResponse response = client.newRequest("http://" + hostHeader + ":" + httpPort + "/")
                        .timeout(15, TimeUnit.SECONDS)
                        .send();
                    assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
                    String date = response.getHeaders().get(HttpHeader.DATE);
                    String msg = "Request to [%s]: Response Header [Date]".formatted(hostHeader);
                    assertThat(msg, date, notNullValue());
                    // asserting an exact value is tricky as the Date header is dynamic,
                    // so we just assert that it has some content and isn't blank
                    assertTrue(StringUtil.isNotBlank(date), msg);
                    assertThat(msg, date, containsString(","));
                    assertThat(msg, date, containsString(":"));
                }
            }
        }
    }

    @Test
    public void testCrossOriginModule() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http,cross-origin,demo-handler"))
        {
            run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS);
            assertThat(run1.getExitValue(), is(0));

            int httpPort1 = Tester.freePort();
            String origin = "http://localhost:" + httpPort1;
            List<String> args = List.of(
                "jetty.http.port=" + httpPort1,
                "jetty.crossorigin.allowedOriginPatterns=" + origin,
                "jetty.crossorigin.allowCredentials=true"
            );
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertThat(run2.awaitConsoleLogsFor("Started oejs.Server", START_TIMEOUT, TimeUnit.SECONDS), is(true));
                startHttpClient();

                ContentResponse response = client.newRequest("http://localhost:" + httpPort1 + "/demo-handler/")
                    .headers(headers -> headers.put(HttpHeader.ORIGIN, origin))
                    .timeout(15, TimeUnit.SECONDS)
                    .send();

                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello World"));
                // Verify that the CORS headers are present.
                assertTrue(response.getHeaders().contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
                assertTrue(response.getHeaders().contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
            }

            int httpPort2 = Tester.freePort();
            args = List.of(
                "jetty.http.port=" + httpPort2,
                // Allow only a different origin, so cross-origin requests will fail.
                "jetty.crossorigin.allowedOriginPatterns=" + origin
            );
            try (JettyHomeTester.Run run2 = distribution.start(args))
            {
                assertThat(run2.awaitConsoleLogsFor("Started oejs.Server", START_TIMEOUT, TimeUnit.SECONDS), is(true));
                startHttpClient();

                ContentResponse response = client.newRequest("http://localhost:" + httpPort2 + "/demo-handler/")
                    .headers(headers -> headers.put(HttpHeader.ORIGIN, "http://localhost:" + httpPort2))
                    .timeout(15, TimeUnit.SECONDS)
                    .send();

                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello World"));
                // Verify that the CORS headers are not present, as the allowed origin is different.
                assertFalse(response.getHeaders().contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
            }
        }
    }

    @Test
    public void testStateTrackingModule() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=state-tracking,http,demo-handler"))
        {
            run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS);
            assertThat(run1.getExitValue(), is(0));

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertThat(run2.awaitConsoleLogsFor("Started oejs.Server", START_TIMEOUT, TimeUnit.SECONDS), is(true));
                startHttpClient();

                ContentResponse response = client.newRequest("http://localhost:" + httpPort + "/demo-handler/")
                    .timeout(15, TimeUnit.SECONDS)
                    .send();

                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello World"));
            }
        }
    }

    @Test
    public void testHTTP2ClientInCoreWebAppProvidedByServer() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http,http2-client-transport,core-deploy"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=DEBUG
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);

            String name = "test-webapp";
            Path webapps = distribution.getJettyBase().resolve("webapps");
            Path webAppDirLib = webapps.resolve(name + ".d").resolve("lib");
            Path webAppJar = distribution.resolveArtifact("org.eclipse.jetty:jetty-test-http2-client-transport-provided-webapp:jar:" + jettyVersion);
            Files.copy(webAppJar, Files.createDirectories(webAppDirLib).resolve("webapp.jar"));
            Files.writeString(webapps.resolve(name + ".xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
                <Configure class="org.eclipse.jetty.server.handler.ContextHandler">
                  <Set name="contextPath">/test</Set>
                  <Set name="handler">
                    <New class="org.eclipse.jetty.test.http2.client.transport.provided.HTTP2ClientTransportProvidedHandler" />
                  </Set>
                </Configure>
                """);

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                URI serverUri = URI.create("http://localhost:" + port + "/test/");
                ContentResponse response = client.newRequest(serverUri)
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee8", "ee9", "ee10"})
    public void testLimitHandlers(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        String[] modules = {
            "http",
            "qos",
            "size-limit",
            "thread-limit",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env)
        };
        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=" + String.join(",", modules)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path jettyLogging = distribution.getJettyBase().resolve("resources/jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=DEBUG
                """;
            Files.writeString(jettyLogging, loggingConfig, StandardOpenOption.TRUNCATE_EXISTING);

            Path war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-simple-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.selectors=1", "jetty.http.port=" + port))
            {
                try
                {
                    assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                    startHttpClient();
                    URI serverUri = URI.create("http://localhost:" + port + "/test/");
                    ContentResponse response = client.newRequest(serverUri)
                        .timeout(15, TimeUnit.SECONDS)
                        .send();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                }
                finally
                {
                    run2.getLogs().forEach(System.err::println);
                }
            }
        }
    }

    @Test
    public void testForwardedWithHTTP2() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=forwarded,test-keystore,http2,requestlog"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.ssl.selectors=1", "jetty.ssl.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                String forwarded = "10.1.1.1";

                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector))));
                URI serverUri = URI.create("https://localhost:" + port + "/");
                ContentResponse response = client.newRequest(serverUri)
                    .headers(h -> h.put("Forwarded", "for=" + forwarded))
                    .timeout(15, TimeUnit.SECONDS)
                    .send();
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                Path logs = distribution.getJettyBase().resolve("logs");
                try (Stream<Path> logsPaths = Files.list(logs))
                {
                    List<Path> logsFiles = logsPaths.toList();
                    assertEquals(1, logsFiles.size());
                    List<String> logLines = await().atMost(5, TimeUnit.SECONDS).until(() ->
                    {
                        try (Stream<String> lines = Files.lines(logsFiles.get(0)))
                        {
                            return lines.toList();
                        }
                    }, Matchers.hasSize(1));
                    assertThat(logLines.get(0), startsWith(forwarded));
                }
            }
        }
    }
}
