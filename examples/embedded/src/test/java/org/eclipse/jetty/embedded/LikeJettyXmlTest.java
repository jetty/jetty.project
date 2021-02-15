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

package org.eclipse.jetty.embedded;

import java.net.URI;
import java.util.Map;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class LikeJettyXmlTest extends AbstractEmbeddedTest
{
    private Server server;
    private URI serverPlainUri;
    private URI serverSslUri;

    @BeforeEach
    public void startServer() throws Exception
    {
        assumeTrue(JettyDistribution.DISTRIBUTION != null, "jetty-distribution not found");

        server = LikeJettyXml.createServer(0, 0, false);
        server.start();

        Map<String, Integer> ports = ServerUtil.fixDynamicPortConfigurations(server);

        // Establish base URI's that use "localhost" to prevent tripping over
        // the "REMOTE ACCESS" warnings in demo-base
        serverPlainUri = URI.create("http://localhost:" + ports.get("plain") + "/");
        serverSslUri = URI.create("https://localhost:" + ports.get("secure") + "/");
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGetTest() throws Exception
    {
        URI uri = serverPlainUri.resolve("/test/");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("Hello"));
    }

    @Test
    public void testGetTestSsl() throws Exception
    {
        URI uri = serverSslUri.resolve("/test/");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("Hello"));
    }
}
