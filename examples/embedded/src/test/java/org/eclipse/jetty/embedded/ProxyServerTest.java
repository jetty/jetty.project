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

import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ProxyServerTest extends AbstractEmbeddedTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = ProxyServer.createServer(0);
        server.start();

        URI uri = server.getURI();
        client.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", uri.getPort()));
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Tag("external")
    @Test
    public void testGetProxiedRFC() throws Exception
    {
        URI uri = URI.create("https://tools.ietf.org/rfc/rfc7230.txt");

        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("Hypertext Transfer Protocol (HTTP/1.1): Message Syntax and Routing"));
    }
}
