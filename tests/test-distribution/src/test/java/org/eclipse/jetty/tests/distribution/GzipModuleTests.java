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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GzipModuleTests extends AbstractJettyHomeTest
{
    @Test
    public void testGzipDefault() throws Exception
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
            "--add-modules=gzip",
            "--add-modules=deploy,webapp,http"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpPort
            };

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/demo/index.html");
                String responseDetails = toResponseDetails(response);
                assertEquals(HttpStatus.OK_200, response.getStatus(), responseDetails);
                assertThat(responseDetails, response.getHeaders().get(HttpHeader.CONTENT_ENCODING), containsString("gzip"));
            }
        }
    }

    @Test
    public void testGzipDefaultExcludedMimeType() throws Exception
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
            "--add-modules=gzip",
            "--add-modules=deploy,webapp,http"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpPort
            };

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/demo/jetty.webp");
                String responseDetails = toResponseDetails(response);
                assertEquals(HttpStatus.OK_200, response.getStatus(), responseDetails);
                assertThat(responseDetails, response.getHeaders().get(HttpHeader.CONTENT_TYPE), containsString("image/webp"));
                assertThat(responseDetails, response.getHeaders().get(HttpHeader.CONTENT_ENCODING), not(containsString("gzip")));
            }
        }
    }

    @Test
    public void testGzipAddWebappSpecificExcludeMimeType() throws Exception
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
            "--add-modules=gzip",
            "--add-modules=deploy,webapp,http"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpPort,
                "jetty.gzip.excludedMimeTypeList=image/vnd.microsoft.icon"
            };

            File war = distribution.resolveArtifact("org.eclipse.jetty.demos:demo-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/demo/jetty.icon");
                String responseDetails = toResponseDetails(response);
                assertEquals(HttpStatus.OK_200, response.getStatus(), responseDetails);
                assertThat(responseDetails, response.getHeaders().get(HttpHeader.CONTENT_ENCODING), not(containsString("gzip")));
                assertThat(responseDetails, response.getHeaders().get(HttpHeader.CONTENT_TYPE), containsString("image/vnd.microsoft.icon"));
            }
        }
    }

    private static String toResponseDetails(ContentResponse response)
    {
        StringBuilder ret = new StringBuilder();
        ret.append(response.toString()).append(System.lineSeparator());
        ret.append(response.getHeaders().toString()).append(System.lineSeparator());
        ret.append(response.getContentAsString()).append(System.lineSeparator());
        return ret.toString();
    }
}
