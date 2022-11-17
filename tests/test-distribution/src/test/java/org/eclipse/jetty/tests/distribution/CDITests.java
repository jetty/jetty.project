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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("JAKARTA namespace for 3rd party librairies")
public class CDITests extends AbstractJettyHomeTest
{
    // Tests from here use these parameters
    public static Stream<Arguments> tests()
    {
        Consumer<JettyHomeTester> renameJettyWebOwbXml = d ->
        {
            try
            {
                Path jettyWebOwbXml = d.getJettyBase().resolve("webapps/demo/WEB-INF/jetty-web-owb.xml");
                Path jettyWebXml = d.getJettyBase().resolve("webapps/demo/WEB-INF/jetty-web.xml");
                Files.move(jettyWebOwbXml, jettyWebXml);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        };

        return Stream.of(
            // -- Weld --
            // Uses test-weld-cdi-webapp
            Arguments.of("weld", "cdi-spi", null), // Weld >= 3.1.2
            Arguments.of("weld", "decorate", null), // Weld >= 3.1.2
            Arguments.of("weld", "cdi-decorate", null), // Weld >= 3.1.3

            // -- Apache OpenWebBeans --
            // Uses test-owb-cdi-webapp
            Arguments.of("owb", "cdi-spi", null)
            // Arguments.of("owb", "decorate", null), // Not supported
            // Arguments.of("owb", "cdi-decorate", null) // Not supported
        );
    }

    /**
     * Tests a WAR file that includes the CDI
     * library in its WEB-INF/lib directory.
     */
    @ParameterizedTest
    @MethodSource("tests")
    public void testCDIIncludedInWebapp(String implementation, String integration, Consumer<JettyHomeTester> configure) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        String jvmArgs = System.getProperty("cdi.tests.jvmArgs");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jvmArgs(jvmArgs == null ? Collections.emptyList() : Arrays.asList(jvmArgs.split("\\s+")))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--approve-all-licenses",
            "--add-modules=http,deploy,annotations,jsp" + (integration == null ? "" : ("," + integration))
        };
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-" + implementation + "-cdi-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");
            if (configure != null)
                configure.accept(distribution);

            int port = distribution.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

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
