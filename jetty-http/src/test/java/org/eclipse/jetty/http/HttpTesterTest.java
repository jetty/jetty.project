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

package org.eclipse.jetty.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class HttpTesterTest
{
    public void testExampleUsage() throws Exception
    {
        try (Socket socket = new Socket("www.google.com", 80))
        {
            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod("POST");
            request.setURI("/search");
            request.setVersion(HttpVersion.HTTP_1_0);
            request.put(HttpHeader.HOST, "www.google.com");
            request.put("Content-Type", "application/x-www-form-urlencoded");
            request.setContent("q=jetty%20server");
            ByteBuffer output = request.generate();

            socket.getOutputStream().write(output.array(), output.arrayOffset() + output.position(), output.remaining());
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            System.err.printf("%s %s %s%n", response.getVersion(), response.getStatus(), response.getReason());
            for (HttpField field : response)
            {
                System.err.printf("%s: %s%n", field.getName(), field.getValue());
            }
            System.err.printf("%n%s%n", response.getContent());
        }
    }

    @Test
    public void testGetRequestBuffer10()
    {
        HttpTester.Request request = HttpTester.parseRequest(
            "GET /uri HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n" +
                "GET /some/other/request /HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );
        MatcherAssert.assertThat(request.getMethod(), is("GET"));
        MatcherAssert.assertThat(request.getUri(), is("/uri"));
        MatcherAssert.assertThat(request.getVersion(), is(HttpVersion.HTTP_1_0));
        MatcherAssert.assertThat(request.get(HttpHeader.HOST), is("localhost"));
        MatcherAssert.assertThat(request.getContent(), is(""));
    }

    @Test
    public void testGetRequestBuffer11()
    {
        HttpTester.Request request = HttpTester.parseRequest(
            "GET /uri HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /some/other/request /HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );
        MatcherAssert.assertThat(request.getMethod(), is("GET"));
        MatcherAssert.assertThat(request.getUri(), is("/uri"));
        MatcherAssert.assertThat(request.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(request.get(HttpHeader.HOST), is("localhost"));
        MatcherAssert.assertThat(request.getContent(), is(""));
    }

    @Test
    public void testPostRequestBuffer10()
    {
        HttpTester.Request request = HttpTester.parseRequest(
            "POST /uri HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: keep-alive\r\n" +
                "Content-Length: 16\r\n" +
                "\r\n" +
                "0123456789ABCDEF" +
                "\r\n" +
                "GET /some/other/request /HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );
        MatcherAssert.assertThat(request.getMethod(), is("POST"));
        MatcherAssert.assertThat(request.getUri(), is("/uri"));
        MatcherAssert.assertThat(request.getVersion(), is(HttpVersion.HTTP_1_0));
        MatcherAssert.assertThat(request.get(HttpHeader.HOST), is("localhost"));
        MatcherAssert.assertThat(request.getContent(), is("0123456789ABCDEF"));
    }

    @Test
    public void testPostRequestBuffer11()
    {
        HttpTester.Request request = HttpTester.parseRequest(
            "POST /uri HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "A\r\n" +
                "0123456789\r\n" +
                "6\r\n" +
                "ABCDEF\r\n" +
                "0\r\n" +
                "\r\n" +
                "GET /some/other/request /HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );
        MatcherAssert.assertThat(request.getMethod(), is("POST"));
        MatcherAssert.assertThat(request.getUri(), is("/uri"));
        MatcherAssert.assertThat(request.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(request.get(HttpHeader.HOST), is("localhost"));
        MatcherAssert.assertThat(request.getContent(), is("0123456789ABCDEF"));
    }

    @Test
    public void testResponseEOFBuffer()
    {
        HttpTester.Response response = HttpTester.parseResponse(
            "HTTP/1.1 200 OK\r\n" +
                "Header: value\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "0123456789ABCDEF"
        );

        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(200));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Header"), is("value"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));
    }

    @Test
    public void testResponseLengthBuffer()
    {
        HttpTester.Response response = HttpTester.parseResponse(
            "HTTP/1.1 200 OK\r\n" +
                "Header: value\r\n" +
                "Content-Length: 16\r\n" +
                "\r\n" +
                "0123456789ABCDEF" +
                "HTTP/1.1 200 OK\r\n" +
                "\r\n"
        );

        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(200));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Header"), is("value"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));
    }

    @Test
    public void testResponseChunkedBuffer()
    {
        HttpTester.Response response = HttpTester.parseResponse(
            "HTTP/1.1 200 OK\r\n" +
                "Header: value\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "A\r\n" +
                "0123456789\r\n" +
                "6\r\n" +
                "ABCDEF\r\n" +
                "0\r\n" +
                "\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "\r\n"
        );

        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(200));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Header"), is("value"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));
    }

    @Test
    public void testResponsesInput() throws Exception
    {
        ByteArrayInputStream stream = new ByteArrayInputStream((
            "HTTP/1.1 200 OK\r\n" +
                "Header: value\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "A\r\n" +
                "0123456789\r\n" +
                "6\r\n" +
                "ABCDEF\r\n" +
                "0\r\n" +
                "\r\n" +
                "HTTP/1.1 400 OK\r\n" +
                "Next: response\r\n" +
                "Content-Length: 16\r\n" +
                "\r\n" +
                "0123456789ABCDEF").getBytes(StandardCharsets.ISO_8859_1)
        );

        HttpTester.Input in = HttpTester.from(stream);

        HttpTester.Response response = HttpTester.parseResponse(in);

        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(200));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Header"), is("value"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));

        response = HttpTester.parseResponse(in);
        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(400));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Next"), is("response"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));
    }

    @Test
    public void testResponsesSplitInput() throws Exception
    {
        PipedOutputStream src = new PipedOutputStream();
        PipedInputStream stream = new PipedInputStream(src)
        {
            @Override
            public synchronized int read(byte[] b, int off, int len) throws IOException
            {
                if (available() == 0)
                    return 0;
                return super.read(b, off, len);
            }
        };

        HttpTester.Input in = HttpTester.from(stream);

        src.write((
                "HTTP/1.1 200 OK\r\n" +
                    "Header: value\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "A\r\n" +
                    "0123456789\r\n" +
                    "6\r\n" +
                    "ABC"
            ).getBytes(StandardCharsets.ISO_8859_1)
        );

        HttpTester.Response response = HttpTester.parseResponse(in);
        assertThat(response, nullValue());
        src.write((
                "DEF\r\n" +
                    "0\r\n" +
                    "\r\n" +
                    "HTTP/1.1 400 OK\r\n" +
                    "Next: response\r\n" +
                    "Content-Length: 16\r\n" +
                    "\r\n" +
                    "0123456789"
            ).getBytes(StandardCharsets.ISO_8859_1)
        );

        response = HttpTester.parseResponse(in);
        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(200));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Header"), is("value"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));

        response = HttpTester.parseResponse(in);
        assertThat(response, nullValue());

        src.write((
                "ABCDEF"
            ).getBytes(StandardCharsets.ISO_8859_1)
        );

        response = HttpTester.parseResponse(in);
        MatcherAssert.assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        MatcherAssert.assertThat(response.getStatus(), is(400));
        MatcherAssert.assertThat(response.getReason(), is("OK"));
        MatcherAssert.assertThat(response.get("Next"), is("response"));
        MatcherAssert.assertThat(response.getContent(), is("0123456789ABCDEF"));
    }
}
