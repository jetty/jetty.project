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

package org.eclipse.jetty.demos;

import java.net.URI;

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

public class OneWebAppWithJspTest extends AbstractEmbeddedTest
{
    private Server server;
    private URI serverLocalUri;

    @BeforeEach
    public void startServer() throws Exception
    {
        assumeTrue(JettyHome.JETTY_HOME != null, "jetty-home not found");

        server = OneWebAppWithJsp.createServer(0);
        server.start();

        // Use URI based on "localhost" to get past "REMOTE ACCESS!" protection of demo war
        serverLocalUri = URI.create("http://localhost:" + server.getURI().getPort() + "/");
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGetDumpInfo() throws Exception
    {
        URI uri = serverLocalUri.resolve("/dump/info");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("getProtocol:&nbsp;</th><td>HTTP/1.1"));
    }

    @Test
    public void testGetJspExpr() throws Exception
    {
        URI uri = serverLocalUri.resolve("/jsp/expr.jsp?A=1");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        String userAgent = client.getUserAgentField().getValue();
        assertThat("Response Content", responseBody, containsString("<td>" + userAgent + "</td>"));
    }

    @Test
    public void testGetJstlExpr() throws Exception
    {
        URI uri = serverLocalUri.resolve("/jsp/jstl.jsp");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("<h1>JSTL Example</h1>"));
        for (int i = 1; i <= 10; i++)
        {
            assertThat("Reponse content (counting)", responseBody, containsString("" + i));
        }
    }
}
