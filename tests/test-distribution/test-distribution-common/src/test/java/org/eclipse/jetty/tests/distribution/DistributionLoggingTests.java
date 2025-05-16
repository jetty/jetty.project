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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistributionLoggingTests extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DistributionLoggingTests.class);

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
            Files.write(julConfig, Arrays.asList("\n", "org.eclipse.jetty.level=FINE"), StandardOpenOption.APPEND);

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
    public void testRequestLogFormatWithSpaces() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] args1 = {"--add-module=server,http,requestlog"};
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
}
