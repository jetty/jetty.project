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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests where we expect the server to not start properly.
 * Presumably due to bad configuration, or bad webapps.
 */
public class BadStartupTest
{
    @Test
    public void testThrowOnUnavailable_BadApp() throws Exception
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
            distribution.installBaseResource("badapp/badapp.xml",
                    "webapps/badapp.xml");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS), "Should have exited");
                assertThat("Should have gotten a non-zero exit code", run2.getExitValue(), not(is(0)));
            }
        }
    }
}
