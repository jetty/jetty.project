//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Tests of GzipHandler behavior with gzip compressed Request content.
 */
public class GzipHandlerInputTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setInflateBufferSize(8192); // enable request inflation

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        servletContextHandler.addServlet(ReadAllInputServlet.class, "/inflate");

        gzipHandler.setHandler(servletContextHandler);
        server.setHandler(gzipHandler);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stop()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
    }

    public static Stream<Arguments> transferScenarios()
    {
        int[] sizes = {
            0, 1, 8191, 8192, 8193, 8194, 8195, 8226, 8227, 8260, 8261, 8262, 8263, 8264,
            8192, 8193, 8194, 8195, 8226, 8227, 8228, 8259, 8260, 8261, 8262, 8263, 8515,
            8516, 8517, 8518, 8773, 8774, 8775, 9216
        };
        List<Arguments> scenarios = new ArrayList<>();
        // Scenarios 1: use Content-Length on request
        for (int size : sizes)
        {
            scenarios.add(Arguments.of(size, true));
        }
        // Scenarios 2: use Transfer-Encoding: chunked on request
        for (int size : sizes)
        {
            scenarios.add(Arguments.of(size, false));
        }
        return scenarios.stream();
    }

    @ParameterizedTest
    @MethodSource("transferScenarios")
    public void testReadGzippedInput(int testLength, boolean sendContentLength) throws Exception
    {
        byte[] rawBuf = new byte[testLength];
        Arrays.fill(rawBuf, (byte)'x');

        byte[] gzipBuf;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos))
        {
            gzipOut.write(rawBuf, 0, rawBuf.length);
            gzipOut.flush();
            gzipOut.finish();
            gzipBuf = baos.toByteArray();
        }

        URI destURI = server.getURI().resolve("/inflate");
        BytesRequestContent bytesRequestContent = new BytesRequestContent(gzipBuf, new byte[0])
        {
            @Override
            public long getLength()
            {
                if (sendContentLength)
                    return super.getLength();
                return -1; // we want chunked transfer-encoding
            }
        };
        Request request = client.newRequest(destURI)
            .method(HttpMethod.POST)
            .headers((headers) -> headers.put(HttpHeader.CONTENT_ENCODING, "gzip"))
            .body(bytesRequestContent);
        ContentResponse response = request.send();

        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContentAsString();
        if (sendContentLength)
            assertThat(responseBody, containsString(String.format("[X-Content-Length]: %d", gzipBuf.length)));
        else
            assertThat(responseBody, containsString("[Transfer-Encoding]: chunked"));

        assertThat(responseBody, containsString("[X-Content-Encoding]: gzip"));
        assertThat(responseBody, containsString(String.format("Read %d bytes", rawBuf.length)));
    }

    public static class ReadAllInputServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            InputStream input = req.getInputStream();
            byte[] buf = input.readAllBytes();
            resp.setCharacterEncoding("utf-8");
            resp.setContentType("text/plain");

            PrintWriter out = resp.getWriter();
            // dump header names & values
            List<String> headerNames = Collections.list(req.getHeaderNames());
            Collections.sort(headerNames);
            for (String headerName : headerNames)
            {
                List<String> headerValues = Collections.list(req.getHeaders(headerName));
                out.printf("header [%s]: %s%n", headerName, String.join(", ", headerValues));
            }
            // dump number of bytes read
            out.printf("Read %d bytes%n", buf.length);
        }
    }
}
