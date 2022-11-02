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

package org.eclipse.jetty.ee9.demos;

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
        client.getProxyConfiguration().addProxy(new HttpProxy("localhost", uri.getPort()));
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
