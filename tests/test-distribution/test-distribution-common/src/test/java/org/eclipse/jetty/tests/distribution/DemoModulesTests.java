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

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemoModulesTests extends AbstractJettyHomeTest
{
    private static Stream<Arguments> provideEnvironmentsToTest()
    {
        String envsToTest = System.getProperty("environmentsToTest", "ee8,ee9,ee10");
        return Arrays.stream(envsToTest.split(",")).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testDemoAddServerClasses(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            try (JettyHomeTester.Run runListConfig = distribution.start("--list-config"))
            {
                assertTrue(runListConfig.awaitFor(5, TimeUnit.SECONDS));
                assertEquals(0, runListConfig.getExitValue());
                // Example of what we expect
                // jetty.webapp.addServerClasses = org.eclipse.jetty.logging.,${jetty.home.uri}/lib/logging/,org.slf4j.,${jetty.base.uri}/lib/bouncycastle/
                String addServerKey = " jetty.webapp.addServerClasses = ";
                String addServerClasses = runListConfig.getLogs().stream()
                    .filter(s -> s.startsWith(addServerKey))
                    .findFirst()
                    .orElseThrow(() ->
                        new NoSuchElementException("Unable to find [" + addServerKey + "]"));
                assertThat("'jetty.webapp.addServerClasses' entry count",
                    addServerClasses.split(",").length,
                    greaterThan(1));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJspDump(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        String baseURI = "http://localhost:%d/%s-demo-jsp".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpsPort,
                "jetty.ssl.port=" + httpsPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/dump.jsp");

                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("PathInfo"));
                assertThat(response.getContentAsString(), not(containsString("<%")));


            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testJstlDemo(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] argsConfig = {
                "--add-modules=http," + toEnvironment("demos", env)
        };

        String baseURI = "http://localhost:%d/%s-demo-jsp".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort,
                    "jetty.httpConfig.port=" + httpsPort,
                    "jetty.ssl.port=" + httpsPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI + "/jstl.jsp");

                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("JSTL Example"));
                assertThat(response.getContentAsString(), containsString("5"));
                assertThat(response.getContentAsString(), containsString("10"));
                assertThat(response.getContentAsString(), not(containsString("<c:forEach")));


            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testAsyncRest(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        String baseURI = "http://localhost:%d/%s-demo-async-rest".formatted(httpPort, env);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpsPort,
                "jetty.ssl.port=" + httpsPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response;

                response = client.GET(baseURI + "/testSerial?items=kayak");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Blocking: kayak"));

                response = client.GET(baseURI + "/testSerial?items=mouse,beer,gnome");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Blocking: mouse,beer,gnome"));

                response = client.GET(baseURI + "/testAsync?items=kayak");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Asynchronous: kayak"));

                response = client.GET(baseURI + "/testAsync?items=mouse,beer,gnome");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Asynchronous: mouse,beer,gnome"));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testSpec(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpsPort,
                "jetty.ssl.port=" + httpsPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", 20, TimeUnit.SECONDS));

                startHttpClient();

                String baseURI = "http://localhost:%d/%s-test-spec".formatted(httpPort, env);

                //test the async listener
                ContentResponse response = client.POST(baseURI + "/asy/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
                assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

                //test the servlet 3.1/4 features
                response = client.POST(baseURI + "/test/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
                assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

                //test dynamic jsp
                response = client.POST(baseURI + "/dynamicjsp/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Programmatically Added Jsp File"));
            }
        }
    }

    @ParameterizedTest
    //@MethodSource("provideEnvironmentsToTest")
    @Disabled("not yet ready for this")
    @ValueSource(strings = "ee10")
    public void testJPMS(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demo", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            int httpPort = distribution.freePort();
            int httpsPort = distribution.freePort();
            String[] argsStart = {
                "--jpms",
                "--debug",
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpsPort,
                "jetty.ssl.port=" + httpsPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse helloResponse = client.GET("http://localhost:" + httpPort + "/test/hello");
                assertEquals(HttpStatus.OK_200, helloResponse.getStatus());

                ContentResponse cssResponse = client.GET("http://localhost:" + httpPort + "/jetty-dir.css");
                assertEquals(HttpStatus.OK_200, cssResponse.getStatus());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testSessionDump(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("demos", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            int httpPort = distribution.freePort();
            int httpsPort = distribution.freePort();
            String[] argsStart = {
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpsPort,
                "jetty.ssl.port=" + httpsPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                String baseURI = "http://localhost:%d/%s-test".formatted(httpPort, env);

                startHttpClient();
                client.setFollowRedirects(true);
                ContentResponse response = client.GET(baseURI + "/session/");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));

                // Submit "New Session"
                Fields form = new Fields();
                form.add("Action", "New Session");
                response = client.POST(baseURI + "/session/")
                    .body(new FormRequestContent(form))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                String content = response.getContentAsString();
                assertThat("Content", content, containsString("<b>test:</b> value<br/>"));
                assertThat("Content", content, containsString("<b>WEBCL:</b> {}<br/>"));

                // Last Location
                URI location = response.getRequest().getURI();

                // Submit a "Set" for a new entry in the cookie
                form = new Fields();
                form.add("Action", "Set");
                form.add("Name", "Zed");
                form.add("Value", "[alpha]");
                response = client.POST(location)
                    .body(new FormRequestContent(form))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                content = response.getContentAsString();
                assertThat("Content", content, containsString("<b>Zed:</b> [alpha]<br/>"));
            }
        }
    }

    @Test
    public void testDemoHandler() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

        int httpPort = distribution.freePort();

        String[] argsConfig = {
                "--add-modules=http,demo-handler"
        };

        String baseURI = "http://localhost:%d/demo-handler/".formatted(httpPort);

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            String[] argsStart = {
                    "jetty.http.port=" + httpPort
            };

            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET(baseURI);

                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Hello World"));
            }
        }
    }
}
