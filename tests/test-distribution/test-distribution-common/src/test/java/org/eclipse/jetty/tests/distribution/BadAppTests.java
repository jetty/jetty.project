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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests where the server is started with a Bad App that will fail in its init phase.
 */
public class BadAppTests extends AbstractJettyHomeTest
{
    /**
     * Start a server where a bad webapp is being deployed.
     * The badapp.war will throw a ServletException during its deploy/init.
     * The badapp.xml contains a {@code <Set name="throwUnavailableOnStartupException">true</Set>}
     *
     * It is expected that the server does not start and exits with an error code
     */
    @ParameterizedTest
    @CsvSource({"ee9", "ee10"})
    public void testXmlThrowOnUnavailableTrue(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http," + toEnvironment("deploy", env)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertThat(run1.getExitValue(), is(0));
            
            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-badinit-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "badapp");
            
            // Setup webapps directory
            distribution.installBaseResource("badapp-" + env + "/badapp_throwonunavailable_true.xml",
                "webapps/badapp.xml");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitFor(START_TIMEOUT, TimeUnit.SECONDS), "Should have exited");
                assertThat("Should have gotten a non-zero exit code", run2.getExitValue(), not(is(0)));
            }
        }
    }

    /**
     * Start a server where a bad webapp is being deployed.
     * The badapp.war will throw a ServletException during its deploy/init.
     * The badapp.xml contains a {@code <Set name="throwUnavailableOnStartupException">false</Set>}
     *
     * It is expected that the server does start and attempts to access the /badapp/ report
     * that it is unavailable.
     */
    
    @ParameterizedTest
    @CsvSource({"ee9", "ee10"})
    public void testXmlThrowOnUnavailableFalse(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http," + toEnvironment("deploy", env)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertThat(run1.getExitValue(), is(0));
            
            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-badinit-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "badapp");

            distribution.installBaseResource("badapp-" + env + "/badapp_throwonunavailable_false.xml",
                "webapps/badapp.xml");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/badapp/");
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus());
                assertThat(response.getContentAsString(), containsString("<h2>HTTP ERROR 503 Service Unavailable</h2>"));
            }
        }
    }

    /**
     * Start a server where a bad webapp is being deployed.
     * The badapp.war will throw a ServletException during its deploy/init.
     * No badapp.xml is used, relying on default values for {@code throwUnavailableOnStartupException}
     *
     * It is expected that the server does start and attempts to access the /badapp/ report
     * that it is unavailable.
     */

    @ParameterizedTest
    @CsvSource({"ee9", "ee10"})
    public void testNoXmlThrowOnUnavailableDefault(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http," + toEnvironment("deploy", env)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertThat(run1.getExitValue(), is(0));
            
            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-badinit-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "badapp");

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port, "jetty.server.dumpAfterStart=true"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                //ContentResponse response = client.GET("http://localhost:" + port + "/badapp/bad/x");
                ContentResponse response = client.GET("http://localhost:" + port + "/badapp/");
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus());
                assertThat(response.getContentAsString(), containsString("<h2>HTTP ERROR 503 Service Unavailable</h2>"));
            }
        }
    }

    /*
    @ParameterizedTest
    @CsvSource({",ee9", ",ee10", "--jpms,ee9", "--jpms,ee10"})
    public void testBadWebSocketWebapp(String arg, String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();
        String mods = "resources,server,http," + 
            toEnvironment("webapp", env) + "," +
            toEnvironment("deploy", env) + "," +
            toEnvironment("jsp", env) + "," + 
            "jmx," +
            toEnvironment("servlet", env) + "," +
            toEnvironment("websocket", env);
        //servlets,
        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=" + mods
        };

        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File badWebApp = distribution.resolveArtifact("org.eclipse.jetty." + env + "tests:" + "jetty-" + env + "-test-bad-websocket-webapp:war:" + jettyVersion);
            distribution.installWarFile(badWebApp, "test");

            int port = distribution.freePort();
            String[] args2 = {arg, "jetty.http.port=" + port};

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertFalse(run2.getLogs().stream().anyMatch(s -> s.contains("LinkageError")));

                startHttpClient();
                WebSocketClient wsClient = new WebSocketClient(client);
                wsClient.start();
                URI serverUri = URI.create("ws://localhost:" + port);

                // Verify /test is not able to establish a WebSocket connection.
                ContentResponse response = client.GET(serverUri.resolve("/test/badonopen/a"));
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus());
            }
        }
    }
    */
}
