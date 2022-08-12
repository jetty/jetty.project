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

package org.eclipse.jetty.tests.distribution.session;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest;
import org.eclipse.jetty.tests.distribution.JettyHomeTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractSessionDistributionTests extends AbstractJettyHomeTest
{

    private String jettyVersion = System.getProperty("jettyVersion");
    private String sessionLogLevel = System.getProperty("sessionLogLevel", "INFO");

    protected JettyHomeTester jettyHomeTester;

    @BeforeEach
    public void prepareJettyHomeTester() throws Exception
    {

        jettyHomeTester = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .env(env())
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10"})
    public void stopRestartWebappTestSessionContentSaved(String environment) throws Exception
    {
        startExternalSessionStorage();

        String modules = "resources,server,http," +
            toEnvironment("webapp", environment) +
            "," +
            toEnvironment("deploy", environment) +
            "," +
            toEnvironment("servlet", environment);
          /*  "," +
            toEnvironment("servlets", environment);*/
        
        List<String> args = new ArrayList<>(Arrays.asList(
                "--create-startd",
                "--approve-all-licenses",
                "--add-module=" + modules + "," + getFirstStartExtraModules()));
        
        args.addAll(getFirstStartExtraArgs());
        String[] argsStart = args.toArray(new String[0]);

        try (JettyHomeTester.Run run1 = jettyHomeTester.start(argsStart))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = jettyHomeTester.resolveArtifact("org.eclipse.jetty." +  environment +
                    ":" + "jetty-" + environment + "-test-simple-session-webapp:war:" + jettyVersion);
            jettyHomeTester.installWarFile(war, "test");

            int port = jettyHomeTester.freePort();
            args = new ArrayList<>(Collections.singletonList("jetty.http.port=" + port));
            args.addAll(getSecondStartExtraArgs());
            argsStart = args.toArray(new String[0]);
            
            //allow the external storage mechanisms to do some config before starting test
            configureExternalSessionStorage(jettyHomeTester.getJettyBase());
            
            try (JettyHomeTester.Run run2 = jettyHomeTester.start(argsStart))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/session?action=CREATE");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("SESSION CREATED"));

                response = client.GET("http://localhost:" + port + "/test/session?action=READ");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
            }

            Path logFile = jettyHomeTester.getJettyBase().resolve("resources").resolve("jetty-logging.properties");
            Files.deleteIfExists(logFile);
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE))
            {
                writer.write("org.eclipse.jetty.session.LEVEL=" + sessionLogLevel);
            }

            try (JettyHomeTester.Run run2 = jettyHomeTester.start(argsStart))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                ContentResponse response = client.GET("http://localhost:" + port + "/test/session?action=READ");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
            }
        }

        stopExternalSessionStorage();
    }

    public Map<String, String> env()
    {
        return Collections.emptyMap();
    }

    public abstract List<String> getFirstStartExtraArgs();

    public abstract String getFirstStartExtraModules();

    public abstract List<String> getSecondStartExtraArgs();

    public abstract void startExternalSessionStorage() throws Exception;

    public abstract void stopExternalSessionStorage() throws Exception;

    public abstract void configureExternalSessionStorage(Path jettyBase) throws Exception;

}
