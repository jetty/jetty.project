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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalConnectorTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.setIdleTimeout(60000);
        _server.addConnector(_connector);
        _server.setHandler(new DumpHandler());
        _server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        _server.stop();
        _server = null;
        _connector = null;
    }

    @Test
    public void testOpenClose() throws Exception
    {
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        _connector.addBean(new Connection.Listener.Adapter()
        {
            @Override
            public void onOpened(Connection connection)
            {
                openLatch.countDown();
            }

            @Override
            public void onClosed(Connection connection)
            {
                closeLatch.countDown();
            }
        });

        _connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOneGET() throws Exception
    {
        String response = _connector.getResponse("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
    }

    @Test
    public void testOneResponse10() throws Exception
    {
        String response = _connector.getResponse("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
    }

    @Test
    public void testOneResponse10KeepAlive() throws Exception
    {
        String response = _connector.getResponse(
            "GET /R1 HTTP/1.0\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
    }

    @Test
    public void testOneResponse10KeepAliveEmpty() throws Exception
    {
        String response = _connector.getResponse(
            "GET /R1?empty=true HTTP/1.0\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("pathInfo=/R1")));
    }

    @Test
    public void testOneResponse11() throws Exception
    {
        String response = _connector.getResponse(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
    }

    @Test
    public void testOneResponse11close() throws Exception
    {
        String response = _connector.getResponse(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
    }

    @Test
    public void testOneResponse11empty() throws Exception
    {
        String response = _connector.getResponse(
            "GET /R1?empty=true HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("pathInfo=/R1")));
    }

    @Test
    public void testOneResponse11chunked() throws Exception
    {
        String response = _connector.getResponse(
            "GET /R1?flush=true HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
        assertThat(response, containsString("\r\n0\r\n"));
    }

    @Test
    public void testThreeResponsePipeline11() throws Exception
    {
        LocalEndPoint endp = _connector.connect();
        endp.addInput(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R3 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );
        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));
        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R3"));
    }

    @Test
    public void testThreeResponse11() throws Exception
    {
        LocalEndPoint endp = _connector.connect();
        endp.addInput(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));

        endp.addInput(
            "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));

        endp.addInput(
            "GET /R3 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );

        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R3"));
    }

    @Test
    public void testThreeResponseClosed11() throws Exception
    {
        LocalEndPoint endp = _connector.connect();
        endp.addInput(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Connection: close\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R3 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );
        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));
        response = endp.getResponse();
        assertThat(response, nullValue());
    }

    @Test
    public void testExpectContinuesAvailable() throws Exception
    {
        LocalEndPoint endp = _connector.connect();
        endp.addInput(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Expect: 100-Continue\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "01234567890\r\n");
        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
        assertThat(response, containsString("0123456789"));
    }

    @Test
    public void testExpectContinues() throws Exception
    {
        LocalEndPoint endp = _connector.executeRequest(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Expect: 100-Continue\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n");
        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 100 Continue"));
        endp.addInput("01234567890\r\n");
        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));
        assertThat(response, containsString("0123456789"));
    }

    @Test
    public void testStopStart() throws Exception
    {
        String response = _connector.getResponse("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));

        _server.stop();
        _server.start();

        response = _connector.getResponse("GET /R2 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));
    }

    @Test
    public void testTwoGETs() throws Exception
    {
        LocalEndPoint endp = _connector.connect();
        endp.addInput(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.0\r\n\r\n");

        String response = endp.getResponse() + endp.getResponse();

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));

        response = response.substring(response.indexOf("</html>") + 8);

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));
    }

    @Test
    public void testTwoGETsParsed() throws Exception
    {
        LocalConnector.LocalEndPoint endp = _connector.executeRequest(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        String response = BufferUtil.toString(endp.waitForResponse(false, 10, TimeUnit.SECONDS), StandardCharsets.ISO_8859_1);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));

        response = BufferUtil.toString(endp.waitForResponse(false, 10, TimeUnit.SECONDS), StandardCharsets.ISO_8859_1);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));
    }

    @Test
    public void testManyGETs() throws Exception
    {
        LocalEndPoint endp = _connector.connect();
        endp.addInput(
            "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R3 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R4 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R5 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /R6 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        String r = "";

        for (String response = endp.getResponse(); response != null; response = endp.getResponse())
        {
            r += response;
        }

        for (int i = 1; i <= 6; i++)
        {
            assertThat(r, containsString("HTTP/1.1 200 OK"));
            assertThat(r, containsString("pathInfo=/R" + i));
            r = r.substring(r.indexOf("</html>") + 8);
        }
    }

    @Test
    public void testGETandGET() throws Exception
    {
        String response = _connector.getResponse("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R1"));

        response = _connector.getResponse("GET /R2 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("pathInfo=/R2"));
    }
}
