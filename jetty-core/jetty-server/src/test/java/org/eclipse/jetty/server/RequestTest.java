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

package org.eclipse.jetty.server;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setIdleTimeout(60000);
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
        connector = null;
    }

    @Test
    public void testEncodedSpace() throws Exception
    {
        String request = """
                GET /fo%6f%20bar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), containsString("httpURI.path=/fo%6f%20bar"));
        assertThat(response.getContent(), containsString("pathInContext=/foo%20bar"));
    }

    @Test
    public void testEncodedPath() throws Exception
    {
        String request = """
                GET /fo%6f%2fbar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), containsString("httpURI.path=/fo%6f%2fbar"));
        assertThat(response.getContent(), containsString("pathInContext=/foo%2Fbar"));
    }
}
