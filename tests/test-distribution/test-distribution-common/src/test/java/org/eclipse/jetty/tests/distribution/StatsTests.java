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

import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
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
            assertEquals(0, run1.getExitValue());

            // Make a context
            Path webappsDir = distribution.getJettyBase().resolve("webapps");
            FS.ensureDirExists(webappsDir.resolve("demo"));

            // Configure server to dump stats on stop, so we can assert on them
            try (FileOutputStream fos = new FileOutputStream(distribution.getJettyBase().resolve("start.d/server.ini").toString(), true))
            {
                fos.write("\njetty.server.dumpBeforeStop=true\n".getBytes(StandardCharsets.UTF_8));
            }

            int httpPort = Tester.freePort();
            int stopPort = httpPort + 1;
            String[] args2 = {
                "jetty.http.port=" + httpPort,
                "STOP.PORT=" + stopPort,
                "STOP.KEY=secret"
            };
            JettyHomeTester.Run run2 = distribution.start(args2);
            assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

            startHttpClient();

            ContentResponse response;
            URI serverBaseURI = URI.create("http://localhost:" + httpPort);

            // Make a few requests to increase the stat values
            response = client.GET(serverBaseURI.resolve("/demo"));
            assertEquals(HttpStatus.OK_200, response.getStatus());
            response = client.GET(serverBaseURI.resolve("/demo/"));
            assertEquals(HttpStatus.OK_200, response.getStatus());
            response = client.GET(serverBaseURI.resolve("/does-not-exist/"));
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            response = client.GET(serverBaseURI.resolve("/does-not-exist-either/"));
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

            // Stop the server
            assertTrue(distribution.start("STOP.PORT=" + stopPort, "STOP.KEY=secret", "--stop").awaitFor(5, TimeUnit.SECONDS));

            // Wait until the server stopped
            assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));

            // Assert stats are as expected
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                run2.getLogs().stream().filter(log -> log.endsWith("+> 1xxResponses: 0")).findFirst().orElse(null) != null &&
                run2.getLogs().stream().filter(log -> log.endsWith("+> 2xxResponses: 2")).findFirst().orElse(null) != null &&
                run2.getLogs().stream().filter(log -> log.endsWith("+> 3xxResponses: 1")).findFirst().orElse(null) != null &&
                run2.getLogs().stream().filter(log -> log.endsWith("+> 4xxResponses: 2")).findFirst().orElse(null) != null &&
                run2.getLogs().stream().filter(log -> log.endsWith("+> 5xxResponses: 0")).findFirst().orElse(null) != null);
        }
    }
}
