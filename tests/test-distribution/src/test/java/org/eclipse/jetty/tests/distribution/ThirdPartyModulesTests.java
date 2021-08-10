//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThirdPartyModulesTests extends AbstractJettyHomeTest
{
    @Test
    @Disabled("not jakarta ready https://github.com/eclipse/jetty.project/issues/6595")
    public void testHawtio() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();

        String[] argsConfig = {
            "--approve-all-licenses",
            "--add-modules=hawtio,http"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(2, TimeUnit.MINUTES));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/hawtio");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<title>Hawtio</title>"));
            }
        }
    }

    @Test
    @Disabled("not jakarta ready https://github.com/eclipse/jetty.project/issues/6595")
    public void testJAMon() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

        int httpPort = distribution.freePort();

        String[] argsConfig = {
                "--approve-all-licenses",
                "--add-modules=jamon,http"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(2, TimeUnit.MINUTES));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/jamon");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<title>JAMon"));
            }
        }
    }

    @Test
    public void testjminix() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

        int httpPort = distribution.freePort();

        String[] argsConfig = {
                "--approve-all-licenses",
                "--add-modules=jminix,http,logging-jcl-capture"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(2, TimeUnit.MINUTES));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    @Disabled("not jakarta ready https://github.com/eclipse/jetty.project/issues/6595")
    public void testjolokia() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

        int httpPort = distribution.freePort();

        String[] argsConfig = {
                "--approve-all-licenses",
                "--add-modules=jolokia,http"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(2, TimeUnit.MINUTES));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/jolokia");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("\"agentType\":\"servlet\""));
            }
        }
    }

}
