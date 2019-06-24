//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("Until issue is fixed at weld -> https://github.com/eclipse/jetty.project/issues/3803")
public class CDITests extends AbstractDistributionTest
{
    /**
     * Tests a WAR file that is CDI complete as it includes the weld
     * library in its WEB-INF/lib directory.
     */
    @Test
    public void testCDI2_IncludedInWebapp() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=http,deploy,annotations,jsp"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-cdi2-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");

            distribution.installBaseResource("cdi/demo_context.xml", "webapps/demo.xml");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/demo/greetings");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                // Confirm Servlet based CDI
                assertThat(response.getContentAsString(), containsString("Hello GreetingsServlet"));
                // Confirm Listener based CDI (this has been a problem in the past, keep this for regression testing!)
                assertThat(response.getHeaders().get("Server"), containsString("CDI-Demo-org.eclipse.jetty.test"));

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Tests a WAR file that is expects CDI to be configured by the server.
     *
     * <p>
     * This means the WAR has the weld libs in its
     * WEB-INF/lib directory.
     * </p>
     *
     * <p>
     * The expectation is that a context xml deployable file is not
     * required when using this `cdi2` module, and the appropriate
     * server side libs are made available to allow weld to function.
     * (the required server side javax.el support comes from the cdi2 module)
     * </p>
     */
    @Test
    public void testCDI2_ConfiguredByServer() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            // standard entries
            "--add-to-start=http,deploy,annotations",
            // cdi2 specific entry (should transitively pull in what it needs, the user should not be expected to know the transitive entries)
            "--add-to-start=cdi2"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-cdi2-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/demo/greetings");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                // Confirm Servlet based CDI
                assertThat(response.getContentAsString(), containsString("Hello GreetingsServlet"));
                // Confirm Listener based CDI (this has been a problem in the past, keep this for regression testing!)
                assertThat(response.getHeaders().get("Server"), containsString("CDI-Demo-org.eclipse.jetty.test"));

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
