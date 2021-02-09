//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.net.URI;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

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
            assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/jsp/dump.jsp");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("PathInfo"));
            assertThat(response.getContentAsString(), not(containsString("<%")));
        }
    }

    @Test
    @Tag("external")
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
            assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));

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
            assertTrue(run1.awaitConsoleLogsFor("Started @", 20, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.POST("http://localhost:" + httpPort + "/test-spec/asy/xx").send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat(response.getContentAsString(), containsString("<span class=\"pass\">PASS</span>"));
            assertThat(response.getContentAsString(), not(containsString("<span class=\"fail\">FAIL</span>")));
        }
    }

    @Test
    public void testJavadocProxy() throws Exception
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
            assertTrue(run.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/proxy/current/");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertThat("Expecting APIdoc contents", response.getContentAsString(), containsString("All&nbsp;Classes"));
        }
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
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
            assertTrue(run.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

            startHttpClient();
            ContentResponse helloResponse = client.GET("http://localhost:" + httpPort + "/test/hello");
            assertEquals(HttpStatus.OK_200, helloResponse.getStatus());

            ContentResponse cssResponse = client.GET("http://localhost:" + httpPort + "/jetty-dir.css");
            assertEquals(HttpStatus.OK_200, cssResponse.getStatus());
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
            assertTrue(run.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

            startHttpClient();
            client.setFollowRedirects(true);
            ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/session/");
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // Submit "New Session"
            Fields form = new Fields();
            form.add("Action", "New Session");
            response = client.POST("http://localhost:" + httpPort + "/test/session/")
                .content(new FormContentProvider(form))
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
                .content(new FormContentProvider(form))
                .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            assertThat("Content", content, containsString("<b>Zed:</b> [alpha]<br/>"));
        }
    }
}
