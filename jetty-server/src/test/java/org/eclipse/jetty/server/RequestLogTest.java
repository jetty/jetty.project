//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Test raw behaviors of RequestLog and how Request / Response objects behave during
 * the call to a RequestLog implementation.
 * <p>
 * This differs from other RequestLog tests as it test the combination of
 * bad requests, and RequestLog implementations that can cause changes
 * in the request or response objects.
 * </p>
 */
public class RequestLogTest
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestLogTest.class);

    public Server createServer(RequestLog requestLog) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setRequestLog(requestLog);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("UTF-8");
                response.setContentType("text/plain");
                response.getWriter().printf("Got %s to %s%n", request.getMethod(), request.getRequestURI());
                baseRequest.setHandled(true);
            }
        });

        return server;
    }

    @Test
    public void testNormalGetRequest() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            server = createServer((request, response) ->
                requestLogLines.add(String.format("method:%s|uri:%s|status:%d", request.getMethod(), request.getRequestURI(), response.getStatus())));
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                StringBuilder req = new StringBuilder();
                req.append("GET /hello HTTP/1.1\r\n");
                req.append("Host: ").append(baseURI.getRawAuthority()).append("\r\n");
                req.append("Connection: close\r\n");
                req.append("\r\n");

                byte[] bufRequest = req.toString().getBytes(UTF_8);

                if (LOG.isDebugEnabled())
                    LOG.debug("--Request--\n" + req);
                out.write(bufRequest);
                out.flush();

                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                IO.copy(in, outBuf);
                String response = outBuf.toString(UTF_8);
                if (LOG.isDebugEnabled())
                    LOG.debug("--Response--\n" + response);

                List<String> responseLines = response.lines()
                    .map(String::trim)
                    .collect(Collectors.toList());

                // Find status code
                String responseStatusLine = responseLines.get(0);
                assertThat("Status Code Response", responseStatusLine, containsString("HTTP/1.1 200"));

                // Find body content (always last line)
                String bodyContent = responseLines.get(responseLines.size() - 1);
                assertThat("Body Content", bodyContent, containsString("Got GET to /hello"));

                String reqlog = requestLogLines.poll(5, TimeUnit.SECONDS);
                assertThat("RequestLog", reqlog, containsString("method:GET|uri:/hello|status:200"));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    public void testNormalPostFormRequest() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            server = createServer((request, response) ->
            {
                // Use a Servlet API that would cause a read of the Request inputStream.
                List<String> paramNames = Collections.list(request.getParameterNames());
                // This should result in no paramNames, as nothing is read during RequestLog execution
                requestLogLines.add(String.format("method:%s|uri:%s|paramNames.size:%d|status:%d", request.getMethod(), request.getRequestURI(), paramNames.size(), response.getStatus()));
            });
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                StringBuilder form = new StringBuilder();
                for (int i = 'a'; i < 'j'; i++)
                {
                    form.append((char)i).append("=").append(i).append("&");
                }

                byte[] bufForm = form.toString().getBytes(UTF_8);

                StringBuilder req = new StringBuilder();
                req.append("POST /hello HTTP/1.1\r\n");
                req.append("Host: ").append(baseURI.getRawAuthority()).append("\r\n");
                req.append("Content-Type: ").append(MimeTypes.Type.FORM_ENCODED).append("\r\n");
                req.append("Content-Length: ").append(bufForm.length).append("\r\n");
                req.append("Connection: close\r\n");
                req.append("\r\n");

                byte[] bufRequest = req.toString().getBytes(UTF_8);

                if (LOG.isDebugEnabled())
                    LOG.debug("--Request--\n" + req);
                out.write(bufRequest);
                out.write(bufForm);
                out.flush();

                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                IO.copy(in, outBuf);
                String response = outBuf.toString(UTF_8);
                if (LOG.isDebugEnabled())
                    LOG.debug("--Response--\n" + response);

                List<String> responseLines = response.lines()
                    .map(String::trim)
                    .collect(Collectors.toList());

                // Find status code
                String responseStatusLine = responseLines.get(0);
                assertThat("Status Code Response", responseStatusLine, containsString("HTTP/1.1 200"));

                // Find body content (always last line)
                String bodyContent = responseLines.get(responseLines.size() - 1);
                assertThat("Body Content", bodyContent, containsString("Got POST to /hello"));

                String reqlog = requestLogLines.poll(5, TimeUnit.SECONDS);
                assertThat("RequestLog", reqlog, containsString("method:POST|uri:/hello|paramNames.size:0|status:200"));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    public void testBadPostFormRequest() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            server = createServer((request, response) ->
            {
                // Use a Servlet API that would cause a read of the Request inputStream.
                List<String> paramNames = Collections.list(request.getParameterNames());
                // This should result in no paramNames, as nothing is read during RequestLog execution
                requestLogLines.add(String.format("method:%s|uri:%s|paramNames.size:%d|status:%d", request.getMethod(), request.getRequestURI(), paramNames.size(), response.getStatus()));
            });
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                StringBuilder form = new StringBuilder();
                for (int i = 'a'; i < 'j'; i++)
                {
                    form.append((char)i).append("=").append(i).append("&");
                }

                byte[] bufForm = form.toString().getBytes(UTF_8);

                StringBuilder req = new StringBuilder();
                req.append("POST /hello HTTP/1.1\r\n");
                req.append("Host: ").append(baseURI.getRawAuthority()).append("\r\n");
                req.append("Content-Type: ").append(MimeTypes.Type.FORM_ENCODED).append("\r\n");
                req.append("Content-Length: ").append(bufForm.length).append("\r\n");
                // add extra Transfer-Encoding: chunked header, making the POST request invalid per HTTP spec
                req.append("Transfer-Encoding: chunked\r\n");
                req.append("Connection: close\r\n");
                req.append("\r\n");

                byte[] bufRequest = req.toString().getBytes(UTF_8);

                if (LOG.isDebugEnabled())
                    LOG.debug("--Request--\n" + req);
                out.write(bufRequest);
                out.write(bufForm);
                out.flush();

                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                IO.copy(in, outBuf);
                String response = outBuf.toString(UTF_8);
                if (LOG.isDebugEnabled())
                    LOG.debug("--Response--\n" + response);

                List<String> responseLines = response.lines()
                    .map(String::trim)
                    .collect(Collectors.toList());

                // Find status code
                String responseStatusLine = responseLines.get(0);
                assertThat("Status Code Response", responseStatusLine, containsString("HTTP/1.1 400 Bad Request"));

                // Find body content (always last line)
                String bodyContent = responseLines.get(responseLines.size() - 1);
                assertThat("Body Content", bodyContent, containsString("reason: Transfer-Encoding and Content-Length"));

                // We should see a requestlog entry for this 400 response
                String reqlog = requestLogLines.poll(3, TimeUnit.SECONDS);
                assertThat("RequestLog", reqlog, containsString("method:POST|uri:/hello|paramNames.size:0|status:400"));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }
}
