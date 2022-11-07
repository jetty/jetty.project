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
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemoModulesTests extends AbstractJettyHomeTest
{
    @Test
    public void testDemoAddServerClasses() throws Exception
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
            "--add-modules=demo"
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

    @Test
    public void testJspDump() throws Exception
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
            "--add-modules=demo"
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
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/demo-jsp/dump.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("PathInfo"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @Tag("external")
    public void testAsyncRest() throws Exception
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
            "--add-modules=demo"
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
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response;

                response = client.GET("http://localhost:" + httpPort + "/demo-async-rest/testSerial?items=kayak");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Blocking: kayak"));

                response = client.GET("http://localhost:" + httpPort + "/demo-async-rest/testSerial?items=mouse,beer,gnome");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Blocking: mouse,beer,gnome"));

                response = client.GET("http://localhost:" + httpPort + "/demo-async-rest/testAsync?items=kayak");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Asynchronous: kayak"));

                response = client.GET("http://localhost:" + httpPort + "/demo-async-rest/testAsync?items=mouse,beer,gnome");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Asynchronous: mouse,beer,gnome"));
            }
        }
    }

    @Test
    public void testSpec() throws Exception
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
            "--add-modules=demo"
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
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();

                //test the async listener
                ContentResponse response = client.POST("http://localhost:" + httpPort + "/test-spec/asy/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
                assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

                //test the servlet 3.1/4 features
                response = client.POST("http://localhost:" + httpPort + "/test-spec/test/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
                assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

                //test dynamic jsp
                response = client.POST("http://localhost:" + httpPort + "/test-spec/dynamicjsp/xx").send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("Programmatically Added Jsp File"));
            }
        }
    }

    @Test
    public void testJPMS() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] argsConfig = {
            "--add-modules=demo"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(20, TimeUnit.SECONDS));
            assertEquals(0, runConfig.getExitValue());

            int httpPort = distribution.freePort();
            int httpsPort = distribution.freePort();
            String[] argsStart = {
                "--jpms",
                "jetty.http.port=" + httpPort,
                "jetty.httpConfig.port=" + httpsPort,
                "jetty.ssl.port=" + httpsPort
            };
            try (JettyHomeTester.Run runStart = distribution.start(argsStart))
            {
                assertTrue(runStart.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse helloResponse = client.GET("http://localhost:" + httpPort + "/test/hello");
                assertEquals(HttpStatus.OK_200, helloResponse.getStatus());

                ContentResponse cssResponse = client.GET("http://localhost:" + httpPort + "/jetty-dir.css");
                assertEquals(HttpStatus.OK_200, cssResponse.getStatus());
            }
        }
    }

    @Test
    public void testSessionDump() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] argsConfig = {
            "--add-modules=demo"
        };

        try (JettyHomeTester.Run runConfig = distribution.start(argsConfig))
        {
            assertTrue(runConfig.awaitFor(5, TimeUnit.SECONDS));
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
                assertTrue(runStart.awaitConsoleLogsFor("Started ", 10, TimeUnit.SECONDS));

                startHttpClient();
                client.setFollowRedirects(true);
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/session/");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));

                // Submit "New Session"
                Fields form = new Fields();
                form.add("Action", "New Session");
                response = client.POST("http://localhost:" + httpPort + "/test/session/")
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
}
