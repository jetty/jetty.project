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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatsTests extends AbstractDistributionTest
{
    @Test
    public void testStatsServlet() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,stats"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path webappsDir = distribution.getJettyBase().resolve("webapps");
            FS.ensureDirExists(webappsDir.resolve("demo"));
            FS.ensureDirExists(webappsDir.resolve("demo/WEB-INF"));

            distribution.installBaseResource("stats-webapp/index.html", "webapps/demo/index.html");
            distribution.installBaseResource("stats-webapp/WEB-INF/web.xml", "webapps/demo/WEB-INF/web.xml");

            int port = distribution.freePort();
            String[] args2 = {
                "jetty.http.port=" + port
            };
            try (DistributionTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();

                ContentResponse response;
                URI serverBaseURI = URI.create("http://localhost:" + port);

                response = client.GET(serverBaseURI.resolve("/demo/index.html"));
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("<h1>Stats Demo</h1>"));

                // ---------------
                // Test XML accept
                response = client.newRequest(serverBaseURI.resolve("/demo/stats"))
                    .method(HttpMethod.GET)
                    .header(HttpHeader.ACCEPT, "text/xml")
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());

                assertThat("Response.contentType", response.getHeaders().get(HttpHeader.CONTENT_TYPE), containsString("text/xml"));

                // Parse it, make sure it's well formed.
                DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
                docBuilderFactory.setValidating(false);
                DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
                try (ByteArrayInputStream input = new ByteArrayInputStream(response.getContent()))
                {
                    Document doc = docBuilder.parse(input);
                    assertNotNull(doc);
                    assertEquals("statistics", doc.getDocumentElement().getNodeName());
                }

                // ---------------
                // Test JSON accept
                response = client.newRequest(serverBaseURI.resolve("/demo/stats"))
                    .method(HttpMethod.GET)
                    .header(HttpHeader.ACCEPT, "application/json")
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());

                assertThat("Response.contentType", response.getHeaders().get(HttpHeader.CONTENT_TYPE), containsString("application/json"));

                Object doc = JSON.parse(response.getContentAsString());
                assertNotNull(doc);
                assertThat(doc, instanceOf(Map.class));
                Map<?, ?> docMap = (Map<?, ?>)doc;
                assertEquals(4, docMap.size());
                assertNotNull(docMap.get("requests"));
                assertNotNull(docMap.get("responses"));
                assertNotNull(docMap.get("connections"));
                assertNotNull(docMap.get("memory"));

                // ---------------
                // Test TEXT accept
                response = client.newRequest(serverBaseURI.resolve("/demo/stats"))
                    .method(HttpMethod.GET)
                    .header(HttpHeader.ACCEPT, "text/plain")
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());

                assertThat("Response.contentType", response.getHeaders().get(HttpHeader.CONTENT_TYPE), containsString("text/plain"));

                String textContent = response.getContentAsString();
                assertThat(textContent, containsString("requests: "));
                assertThat(textContent, containsString("responses: "));
                assertThat(textContent, containsString("connections: "));
                assertThat(textContent, containsString("memory: "));

                // ---------------
                // Test HTML accept
                response = client.newRequest(serverBaseURI.resolve("/demo/stats"))
                    .method(HttpMethod.GET)
                    .header(HttpHeader.ACCEPT, "text/html")
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus());

                assertThat("Response.contentType", response.getHeaders().get(HttpHeader.CONTENT_TYPE), containsString("text/html"));

                String htmlContent = response.getContentAsString();
                // Look for things that indicate it's a well formed HTML output
                assertThat(htmlContent, containsString("<html>"));
                assertThat(htmlContent, containsString("<body>"));
                assertThat(htmlContent, containsString("<em>requests</em>: "));
                assertThat(htmlContent, containsString("<em>responses</em>: "));
                assertThat(htmlContent, containsString("<em>connections</em>: "));
                assertThat(htmlContent, containsString("<em>memory</em>: "));
                assertThat(htmlContent, containsString("</body>"));
                assertThat(htmlContent, containsString("</html>"));
            }
        }
    }
}
