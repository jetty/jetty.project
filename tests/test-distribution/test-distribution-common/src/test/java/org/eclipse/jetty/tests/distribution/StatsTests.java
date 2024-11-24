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

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatsTests extends AbstractJettyHomeTest
{
    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10", "ee11"})
    public void testStatsServlet(String env) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-modules=resources,server,http,statistics," + toEnvironment("webapp", env) + "," + toEnvironment("deploy", env)
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            run1.getLogs().forEach(System.err::println);
            assertEquals(0, run1.getExitValue());

            // Make a context
            Path webappsDir = distribution.getJettyBase().resolve("webapps");
            FS.ensureDirExists(webappsDir.resolve("demo"));

            // TODO add some actual content to it

            int port = Tester.freePort();
            String[] args2 = {
                "jetty.http.port=" + port
            };
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();

                ContentResponse response;
                URI serverBaseURI = URI.create("http://localhost:" + port);

                response = client.GET(serverBaseURI.resolve("/demo/"));
                assertEquals(HttpStatus.OK_200, response.getStatus());

                // TODO test the StatisticsHandler somehow
            }
        }
    }
}
