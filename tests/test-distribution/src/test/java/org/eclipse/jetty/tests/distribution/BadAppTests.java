//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests where the server is started with a Bad App that will fail in its init phase.
 */
public class BadAppTests extends AbstractDistributionTest
{
    /**
     * Start a server where a bad webapp is being deployed.
     * The badapp.war will throw a ServletException during its deploy/init.
     * The badapp.xml contains a {@code <Set name="throwUnavailableOnStartupException">true</Set>}
     *
     * It is expected that the server does not start and exits with an error code
     */
    @Test
    public void testXmlThrowOnUnavailableTrue() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (DistributionTester.Run run1 = distribution.start("--add-to-start=http,deploy"))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertThat(run1.getExitValue(), is(0));

            // Setup webapps directory
            distribution.installBaseResource("badapp/badapp.war",
                "webapps/badapp.war");
            distribution.installBaseResource("badapp/badapp_throwonunavailable_true.xml",
                "webapps/badapp.xml");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS), "Should have exited");
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
    @Test
    public void testXmlThrowOnUnavailableFalse() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (DistributionTester.Run run1 = distribution.start("--add-to-start=http,deploy"))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertThat(run1.getExitValue(), is(0));

            // Setup webapps directory
            distribution.installBaseResource("badapp/badapp.war",
                "webapps/badapp.war");
            distribution.installBaseResource("badapp/badapp_throwonunavailable_false.xml",
                "webapps/badapp.xml");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/badapp/");
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus());
                assertThat(response.getContentAsString(), containsString("<h2>HTTP ERROR 503 Service Unavailable</h2>"));
                assertThat(response.getContentAsString(), containsString("<tr><th>URI:</th><td>/badapp/</td></tr>"));
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
    @Test
    public void testNoXmlThrowOnUnavailableDefault() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (DistributionTester.Run run1 = distribution.start("--add-to-start=http,deploy"))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertThat(run1.getExitValue(), is(0));

            // Setup webapps directory
            distribution.installBaseResource("badapp/badapp.war",
                "webapps/badapp.war");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/badapp/");
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus());
                assertThat(response.getContentAsString(), containsString("<h2>HTTP ERROR 503 Service Unavailable</h2>"));
                assertThat(response.getContentAsString(), containsString("<tr><th>URI:</th><td>/badapp/</td></tr>"));
            }
        }
    }
}
