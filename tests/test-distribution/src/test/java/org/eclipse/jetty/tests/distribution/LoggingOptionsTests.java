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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoggingOptionsTests extends AbstractJettyHomeTest
{
    public static Stream<Arguments> validLoggingModules()
    {
        return Stream.of(
            Arguments.of("logging-jetty",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar",
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]jetty-slf4j-impl-.*\\.jar"),
                Arrays.asList(
                    "logging/slf4j",
                    "logging-jetty"
                )
            ),
            Arguments.of("logging-logback",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]logback-classic-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]logback-core-.*\\.jar"
                ),
                Arrays.asList(
                    "logging/slf4j",
                    "logging-logback"
                )
            ),
            Arguments.of("logging-jul",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-jdk14-.*\\.jar"
                ),
                Arrays.asList(
                    "logging/slf4j",
                    "logging-jul"
                )
            ),
            Arguments.of("logging-log4j1",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-reload4j-.*\\.jar"
                ),
                Arrays.asList(
                    "logging/slf4j",
                    "logging-log4j1"
                )
            ),
            Arguments.of("logging-log4j2",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]log4j-slf4j2-impl-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]log4j-api-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]log4j-core-.*\\.jar"
                ),
                Arrays.asList(
                    "logging/slf4j",
                    "logging-log4j2"
                )
            ),
            // Disabled, as slf4j noop is not supported by output/log monitoring of AbstractJettyHomeTest
            /* Arguments.of("logging-noop",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar"
                ), Arrays.asList(
                    "logging/slf4j",
                    "logging-log4j2"
                )
            ),*/
            Arguments.of("logging-logback,logging-jcl-capture,logging-jul-capture,logging-log4j1-capture",
                Arrays.asList(
                    "\\$\\{jetty.home\\}[/\\\\]lib[/\\\\]logging[/\\\\]slf4j-api-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]logback-classic-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]logback-core-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]jcl-over-slf4j-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]jul-to-slf4j-.*\\.jar",
                    "\\$\\{jetty.base\\}[/\\\\]lib[/\\\\]logging[/\\\\]log4j-over-slf4j-.*\\.jar"
                ),
                Arrays.asList(
                    "logging/slf4j",
                    "logging-logback",
                    "logging-jcl-capture",
                    "logging-jul-capture",
                    "logging-log4j1-capture"
                )
            )
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("validLoggingModules")
    public void testLoggingConfiguration(String loggingModules, List<String> expectedClasspathEntries, List<String> expectedEnabledModules) throws Exception
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
            "--add-modules=resources,server,http,webapp,deploy,jsp,servlets",
            "--add-modules=" + loggingModules
        };
        try (JettyHomeTester.Run installRun = distribution.start(args1))
        {
            assertTrue(installRun.awaitFor(10, TimeUnit.SECONDS));
            assertEquals(0, installRun.getExitValue());

            try (JettyHomeTester.Run listConfigRun = distribution.start("--list-config"))
            {
                assertTrue(listConfigRun.awaitFor(10, TimeUnit.SECONDS));
                assertEquals(0, listConfigRun.getExitValue());

                List<String> rawConfigLogs = new ArrayList<>();
                rawConfigLogs.addAll(listConfigRun.getLogs());

                for (String expectedEnabledModule : expectedEnabledModules)
                {
                    containsEntryWith("Expected Enabled Module", rawConfigLogs, expectedEnabledModule);
                }

                for (String expectedClasspathEntry : expectedClasspathEntries)
                {
                    containsEntryWith("Expected Classpath Entry", rawConfigLogs, expectedClasspathEntry);
                }
            }

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (JettyHomeTester.Run requestRun = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(requestRun.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    private void containsEntryWith(String reason, List<String> logs, String expectedEntry)
    {
        Pattern pat = Pattern.compile(expectedEntry);
        assertThat("Count of matches for [" + expectedEntry + "]", logs.stream().filter(pat.asPredicate()).count(), greaterThanOrEqualTo(1L));
    }
}
