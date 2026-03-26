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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the requestlog-slf4j module.
 */
public class RequestLogSLF4JModuleTest extends AbstractJettyHomeTest
{
    /**
     * Test that the requestlog-slf4j module can be enabled and logs requests to SLF4J.
     */
    @Test
    public void testRequestLogSLF4JModule() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-module=server,http,requestlog-slf4j,logging-log4j2"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2-requestlog.xml"),
                distribution.getJettyBase().resolve("resources").resolve("log4j2.xml"),
                StandardCopyOption.REPLACE_EXISTING);

            // Verify the module ini file was created
            Path requestLogIni = distribution.getJettyBase().resolve("start.d/requestlog-slf4j.ini");
            assertTrue(requestLogIni.toFile().exists(), "requestlog-slf4j.ini should exist");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient(false);

                // Make a request that will be logged
                ContentResponse response = client.GET("http://localhost:" + port + "/test");
                assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

                // Log4j2 writes to file, not console - wait for log file
                Path logFile = distribution.getJettyBase().resolve("logs").resolve("jetty.log");

                // Wait for request log entry to appear in the log file
                await().atMost(10, TimeUnit.SECONDS).until(() ->
                {
                    try (Stream<String> lines = Files.lines(logFile))
                    {
                        return lines.anyMatch(line -> line.contains("GET /test HTTP/"));
                    }
                });

                // Verify the log entry contains expected request information
                try (Stream<String> lines = Files.lines(logFile))
                {
                    String requestLogLine = lines
                        .filter(line -> line.contains("GET /test HTTP/"))
                        .findFirst()
                        .orElse(null);

                    assertNotNull(requestLogLine, "Request log entry should appear in log file");
                    assertThat("Request log should contain status code", requestLogLine, containsString("404"));
                }

                run2.stop();
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Test that a custom logger name can be configured.
     */
    @Test
    public void testRequestLogSLF4JWithCustomLogger() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-module=server,http,requestlog-slf4j,logging-log4j2"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2-requestlog.xml"),
                    distribution.getJettyBase().resolve("resources").resolve("log4j2.xml"),
                    StandardCopyOption.REPLACE_EXISTING);

            // Configure custom logger name
            Path requestLogIni = distribution.getJettyBase().resolve("start.d/requestlog-slf4j.ini");
            String content = Files.readString(requestLogIni);
            content += "\njetty.requestlog.slf4j.loggerName=request_logs\n";
            Files.writeString(requestLogIni, content);

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient(false);
                ContentResponse response = client.GET("http://localhost:" + port + "/");
                assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
                Path logFile = distribution.getJettyBase().resolve("logs").resolve("jetty_custom.log");
                await().atMost(10, TimeUnit.SECONDS).until(() -> Files.exists(logFile));
                // Wait for request log entry with custom logger name
                await().atMost(10, TimeUnit.SECONDS).until(() ->
                {
                    try (Stream<String> lines = Files.lines(logFile))
                    {
                        return lines.anyMatch(line -> line.contains("request_logs"));
                    }
                });

                run2.stop();
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Test that the requestlog-slf4j module works with Logback logging to a file.
     */
    @Test
    public void testRequestLogSLF4JWithLogback() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-module=server,http,requestlog-slf4j,logging-logback"))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/logback-file.xml"),
                distribution.getJettyBase().resolve("resources").resolve("logback.xml"),
                StandardCopyOption.REPLACE_EXISTING);

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient(false);

                ContentResponse response = client.GET("http://localhost:" + port + "/test");
                assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
                // Logback writes to file - wait for log file
                Path logFile = distribution.getJettyBase().resolve("logs").resolve("jetty.log");
                await().atMost(10, TimeUnit.SECONDS).until(() -> Files.exists(logFile));
                // Wait for request log entry to appear in the log file
                await().atMost(10, TimeUnit.SECONDS).until(() ->
                {
                    try (Stream<String> lines = Files.lines(logFile))
                    {
                        return lines.anyMatch(line -> line.contains("GET /test HTTP/"));
                    }
                });

                // Verify the log entry contains expected request information
                try (Stream<String> lines = Files.lines(logFile))
                {
                    String requestLogLine = lines
                        .filter(line -> line.contains("GET /test HTTP/"))
                        .findFirst()
                        .orElse(null);

                    assertNotNull(requestLogLine, "Request log entry should appear in log file");
                    assertThat("Request log should contain status code", requestLogLine, containsString("404"));
                }

                run2.stop();
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            }
        }
    }
}
