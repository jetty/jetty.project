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

package org.eclipse.jetty;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.example.ChatServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ChatServletTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        ServletHolder dispatch = context.addServlet(ChatServlet.class, "/chat/*");
        dispatch.setInitParameter("asyncTimeout", "500");
        server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testLogin() throws Exception
    {
        assertResponse("user=test&join=true&message=has%20joined!", "{\"from\":\"test\",\"chat\":\"has joined!\"}");
    }

    @Test
    public void testChat() throws Exception
    {
        assertResponse("user=test&join=true&message=has%20joined!", "{\"from\":\"test\",\"chat\":\"has joined!\"}");
        String response = connector.getResponse(createRequestString("user=test&message=message"));
        assertThat(response.contains("{"), is(false)); // make sure we didn't get a json body
    }

    @Test
    public void testPoll() throws Exception
    {
        assertResponse("user=test", "{action:\"poll\"}");
    }

    private void assertResponse(String requestBody, String expectedResponse) throws Exception
    {
        String response = connector.getResponse(createRequestString(requestBody));
        assertThat(response.contains(expectedResponse), is(true));
    }

    private String createRequestString(String body)
    {
        return "POST /chat/ HTTP/1.1\r\n" +
            "Host: tester\r\n" +
            "Content-length: " + body.length() + "\r\n" +
            "Content-type: application/x-www-form-urlencoded\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            body;
    }
}
