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
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeployModulesTests extends AbstractJettyHomeTest
{
    @ParameterizedTest
    @ValueSource(strings = {"static-deploy", "core-deploy", "ee8-deploy", "ee9-deploy", "ee10-deploy"})
    public void testDeployModulesOrder(String deployModule) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        // Add deployModule before ee11-deploy to verify that the module before/after directives are correct.
        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=http,%s,ee11-deploy".formatted(deployModule)))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            // Create a core web application, but should be deployed as ee11.
            Path jettyBase = distribution.getJettyBase();
            Path coreWebApp = jettyBase.resolve("webapps").resolve("core-app");
            Path coreWebAppStatic = coreWebApp.resolve("static");
            Files.createDirectories(coreWebAppStatic);
            Files.writeString(coreWebAppStatic.resolve("index.html"), "INDEX", StandardOpenOption.CREATE);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS), () -> String.join(System.lineSeparator(), run2.getLogs()));

                startHttpClient();

                // The web application should be deployed as ee11, not as core,
                // so /static/index.html is a 200 (as core would return a 404).
                ContentResponse response = client.newRequest("localhost", httpPort)
                    .path("/core-app/static/index.html")
                    .send();

                assertThat(response.getStatus(), is(HttpStatus.OK_200));
            }
        }
    }
}
