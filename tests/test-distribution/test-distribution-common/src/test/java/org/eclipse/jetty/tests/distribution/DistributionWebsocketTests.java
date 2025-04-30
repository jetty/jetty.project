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

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistributionWebsocketTests extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DistributionWebsocketTests.class);

    @ParameterizedTest
    @CsvSource({
        "http,ee9,false,",
        "http,ee9,true",
        "https,ee9,false",
        "http,ee10,false,",
        "http,ee10,true",
        "https,ee10,false",
        "http,ee11,false",
        "http,ee11,true",
        "https,ee11,false"})
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
            "resources", "server", "jmx", "logging-log4j2",
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

            Files.copy(Paths.get("src/test/resources/log4j2-debug.xml"),
                    distribution.getJettyBase().resolve("resources").resolve("log4j2.xml"),
                    StandardCopyOption.REPLACE_EXISTING);

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
                String content = response.getContentAsString();
                assertThat("Response: " + content + System.lineSeparator() + "Logs:" + String.join(System.lineSeparator(), run1.getLogs()),
                        response.getStatus(), is(HttpStatus.OK_200));
                assertThat(content, containsString("WebSocketEcho: success"));
                assertThat(content, containsString("ConnectTimeout: 4999"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "http,ee9,false,",
        "http,ee9,true",
        "https,ee9,false",
        "http,ee10,false,",
        "http,ee10,true",
        "https,ee10,false",
        "http,ee11,false",
        "http,ee11,true",
        "https,ee11,false"})
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

    @ParameterizedTest
    @CsvSource(value = {"ee9,false", "ee10,false", "ee10,true", "ee11,false", "ee11,true"})
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
            "resources", "http", "jmx", "debuglog", "logging-log4j2",
            toEnvironment("webapp", env),
            toEnvironment("deploy", env),
            toEnvironment("websocket-jakarta", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertThat("Logs:" + String.join(System.lineSeparator(), run1.getLogs()),
                    run1.awaitFor(10, TimeUnit.SECONDS), is(true));
            assertThat("Logs:" + String.join(System.lineSeparator(), run1.getLogs()),
                    run1.getExitValue(), is(0));

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-websocket-webapp:war:" + jettyVersion);
            distribution.installWar(webApp, "test1");
            distribution.installWar(webApp, "test2");

            Files.copy(Paths.get("src/test/resources/log4j2-debug.xml"),
                    distribution.getJettyBase().resolve("resources").resolve("log4j2.xml"),
                    StandardCopyOption.REPLACE_EXISTING);

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

}
