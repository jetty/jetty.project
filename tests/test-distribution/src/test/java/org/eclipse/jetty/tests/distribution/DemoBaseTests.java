//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.net.URI;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemoBaseTests extends AbstractDistributionTest
{
    @Test
    public void testJspDump() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/jsp/dump.jsp");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("PathInfo"));
            assertThat(response.getContentAsString(), not(containsString("<%")));
        }
    }

    @Test
    public void testAsyncRest() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response;

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testSerial?items=kayak");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Blocking: kayak"));

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testSerial?items=mouse,beer,gnome");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Blocking: mouse,beer,gnome"));

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testAsync?items=kayak");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Asynchronous: kayak"));

            response = client.GET("http://localhost:" + httpPort + "/async-rest/testAsync?items=mouse,beer,gnome");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("Asynchronous: mouse,beer,gnome"));
        }
    }

    @Test
    public void testSpec() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        assertThat("httpPort != httpsPort", httpPort, is(not(httpsPort)));

        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitConsoleLogsFor("Started Server@", 20, TimeUnit.SECONDS));

            startHttpClient();

            //test the async listener
            ContentResponse response = client.POST("http://localhost:" + httpPort + "/test-spec/asy/xx").send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
            assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

            //test the servlet 3.1/4 features
            response = client.POST("http://localhost:" + httpPort + "/test-spec/test/xx").send();
            assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
            assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));

            //test dynamic jsp
            response = client.POST("http://localhost:" + httpPort + "/test-spec/dynamicjsp/xx").send();
            assertThat(response.getContentAsString(), containsString("Programmatically Added Jsp File"));
        }
    }

    @Test
    public void testJPMS() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        String[] args = {
            "--jpms",
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };
        try (DistributionTester.Run run = distribution.start(args))
        {
            assertTrue(run.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/hello");
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testSessionDump() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(Paths.get("demo-base"))
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        int httpPort = distribution.freePort();
        int httpsPort = distribution.freePort();
        String[] args = {
            "jetty.http.port=" + httpPort,
            "jetty.httpConfig.port=" + httpsPort,
            "jetty.ssl.port=" + httpsPort
        };
        try (DistributionTester.Run run = distribution.start(args))
        {
            assertTrue(run.awaitConsoleLogsFor("Started ", 10, TimeUnit.SECONDS));

            startHttpClient();
            client.setFollowRedirects(true);
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/session/");
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // Submit "New Session"
            Fields form = new Fields();
            form.add("Action", "New Session");
            response = client.POST("http://localhost:" + httpPort + "/test/session/")
                .body(new FormRequestContent(form))
                .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
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
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            assertThat("Content", content, containsString("<b>Zed:</b> [alpha]<br/>"));
        }
    }
}
