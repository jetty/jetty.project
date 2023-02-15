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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicListenerTests extends AbstractJettyHomeTest
{

    @ParameterizedTest
    @ValueSource(strings = {"ee8", "ee9", "ee10"})
    public void testSimpleWebAppWithJSP(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyBase(jettyBase)
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args = {
            "--approve-all-licenses",
            "--add-modules=resources,server,http,jmx",
            "--add-modules=" + toEnvironment("demo-jetty", env)
        };
        try (JettyHomeTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());
        }

        int port = distribution.freePort();
        try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
        {
            assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.GET("http://localhost:" + port + "/" + env + "-test/testservlet/foo");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            System.out.println(content);
            assertThat(content, containsString("All Good"));
            assertThat(content, containsString("requestInitialized"));
            assertThat(content, containsString("requestInitialized"));
            assertThat(content, not(containsString("<%")));
        }
    }
}
