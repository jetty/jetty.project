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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicListenerTests
    extends AbstractJettyHomeTest
{
    @Disabled //TODO websocket.mod broken
    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void testSimpleWebAppWithJSP(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyBase(jettyBase)
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=resources,server,http,jmx,websocket," +
                toEnvironment("webapp", env) + "," +
                toEnvironment("deploy", env) + "," +
                toEnvironment("apache-jsp", env) + "," +
                toEnvironment("security", env)
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty." + env + ".demos:jetty-" + env + "-demo-jetty-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            Path etc = Paths.get(jettyBase.toString(), "etc");
            if (!Files.exists(etc))
            {
                Files.createDirectory(etc);
            }

            Files.copy(Paths.get("src/test/resources/realm.ini"),
                Paths.get(jettyBase.toString(), "start.d").resolve("realm.ini"));
            Files.copy(Paths.get("src/test/resources/realm.properties"),
                etc.resolve("realm.properties"));
            Files.copy(Paths.get("src/test/resources/test-realm.xml"),
                       etc.resolve("test-realm.xml"));

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/testservlet/foo");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                String content = response.getContentAsString();
                System.out.println(content);
                assertThat(content, containsString("All Good"));
                assertThat(content, containsString("requestInitialized"));
                assertThat(content, containsString("requestInitialized"));
                assertThat(content, not(containsString("<%")));
            }
        }
        finally
        {
            IO.delete(jettyBase.toFile());
        }
    }

}
