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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistributionJSPTests extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DistributionJSPTests.class);

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10", "ee11"})
    public void testSimpleWebAppWithJSPAndJSTL(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        String mods = String.join(",",
                "resources", "server", "http", "jmx",
                toEnvironment("webapp", env),
                toEnvironment("deploy", env),
                toEnvironment("apache-jsp", env),
                toEnvironment("glassfish-jstl", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--create-start-ini", "--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            // Verify that --create-start-ini works
            assertTrue(Files.exists(distribution.getJettyBase().resolve("start.ini")));

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/jstl.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee10", "ee11"})
    public void testSimpleWebAppWithJSPOnModulePath(String env) throws Exception
    {
        // Testing with env=ee9 is not possible because jakarta.transaction:1.x
        // does not have a proper module-info.java, so JPMS resolution will fail.
        // For env=ee10, jakarta.transaction:2.x has a proper module-info.java.
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        String mods = String.join(",",
                "resources", "server", "http", "jmx",
                toEnvironment("webapp", env),
                toEnvironment("deploy", env),
                toEnvironment("glassfish-jstl", env),
                toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port))
            {
                assertTrue(run2.awaitForJettyStart(true));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));

                response = client.GET("http://localhost:" + port + "/test/jstl.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), not(containsString("<c:")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10", "ee11"})
    public void testSimpleWebAppWithJSPOverH2C(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        testSimpleWebAppWithJSPOverHTTP2(env, false, jettyBase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10", "ee11"})
    public void testSimpleWebAppWithJSPOverH2(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        testSimpleWebAppWithJSPOverHTTP2(env, true, jettyBase);
    }

    private void testSimpleWebAppWithJSPOverHTTP2(String env, boolean ssl, Path jettyBase) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String mods = String.join(",",
            (ssl ? "http2,test-keystore" : "http2c"),
            toEnvironment("deploy", env),
            toEnvironment("apache-jsp", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--add-modules=" + mods))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            String portProp = ssl ? "jetty.ssl.port" : "jetty.http.port";
            try (JettyHomeTester.Run run2 = distribution.start(portProp + "=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                ClientConnector connector = new ClientConnector();
                connector.setSslContextFactory(new SslContextFactory.Client(true));
                HTTP2Client h2Client = new HTTP2Client(connector);
                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP2(h2Client)));
                ContentResponse response = client.GET((ssl ? "https" : "http") + "://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee9", "ee10", "ee11"})
    public void testLog4j2ModuleWithSimpleWebAppWithJSP(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        String mods = String.join(",",
                "resources", "server", "http", "logging-log4j2",
                toEnvironment("webapp", env),
                toEnvironment("deploy", env),
                toEnvironment("apache-jsp", env),
                toEnvironment("servlet", env)
        );
        try (JettyHomeTester.Run run1 = distribution.start("--approve-all-licenses", "--add-modules=" + mods))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());
            LOG.info(run1.logs().get());
            assertTrue(Files.exists(distribution.getJettyBase().resolve("resources/log4j2.xml")));

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-jsp-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int port = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitForJettyStart());
                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("JSP Examples"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
                assertTrue(Files.exists(distribution.getJettyBase().resolve("resources/log4j2.xml")));
            }
        }
    }

}
