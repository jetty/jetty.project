//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ByteCountingTest
{
    private Server server;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);

        ByteCounterChannelListener byteCounterChannelListener = new ByteCounterChannelListener();
        byteCounterChannelListener.addListener(new ByteCounterLogger());
        connector.addBean(byteCounterChannelListener);

        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setCharacterEncoding("utf-8");
                response.getOutputStream().println("Hello ByteCountingTest");
                baseRequest.setHandled(true);
            }
        });

        server.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(server);
    }

    private void dump(HttpTester.Response response)
    {
        System.err.printf("%s %d %s%n", response.getVersion(), response.getStatus(), response.getReason());
        System.err.println(response);
        System.err.println(response.getContent());
    }

    private String makeHttpRequests(CharSequence rawRequest) throws IOException
    {
        URI baseURI = server.getURI().resolve("/");
        String host = baseURI.getHost();
        int port = baseURI.getPort();
        try (Socket socket = new Socket(host, port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream())
        {
            out.write(rawRequest.toString().getBytes(UTF_8));
            out.flush();

            return IO.toString(in, UTF_8);
        }
    }

    @Test
    public void testSimpleGET() throws IOException
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET / HTTP/1.1\r\n");
        rawRequest.append("Host: localhost:").append(server.getURI().getPort()).append("\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = makeHttpRequests(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        dump(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testSimpleGETError400() throws IOException
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET / HTTP/1.1\r\n");
        rawRequest.append("Host: not a vali=d header\r\n");
        rawRequest.append("X Foo: Messy\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = makeHttpRequests(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        dump(response);
        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testConnectionReuse() throws IOException
    {

        URI baseURI = server.getURI().resolve("/");
        String host = baseURI.getHost();
        int port = baseURI.getPort();
        try (Socket socket = new Socket(host, port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream())
        {
            StringBuilder rawRequest1 = new StringBuilder();
            rawRequest1.append("GET / HTTP/1.1\r\n");
            rawRequest1.append("Host: localhost:").append(server.getURI().getPort()).append("\r\n");
            rawRequest1.append("\r\n");

            out.write(rawRequest1.toString().getBytes(UTF_8));

            StringBuilder rawRequest2 = new StringBuilder();
            rawRequest2.append("GET /Eclipse HTTP/1.1\r\n");
            rawRequest2.append("Host: localhost:").append(server.getURI().getPort()).append("\r\n");
            rawRequest2.append("\r\n");

            out.write(rawRequest2.toString().getBytes(UTF_8));

            StringBuilder rawRequest3 = new StringBuilder();
            rawRequest3.append("GET /Jetty HTTP/1.1\r\n");
            rawRequest3.append("Host: localhost:").append(server.getURI().getPort()).append("\r\n");
            rawRequest3.append("Connection: close\r\n");
            rawRequest3.append("\r\n");

            out.write(rawRequest3.toString().getBytes(UTF_8));
            out.flush();

            HttpTester.Response response;

            response = HttpTester.parseResponse(in);
            dump(response);
            response = HttpTester.parseResponse(in);
            dump(response);
            response = HttpTester.parseResponse(in);
            dump(response);
        }
    }

    public static class DumpServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            String audience = "World";
            if (req.getPathInfo() != null)
            {
                audience = req.getPathInfo();
                while (audience.startsWith("/"))
                {
                    audience = audience.substring(1);
                }
            }
            resp.getOutputStream().print("Hello " + audience);
        }
    }

    public static class ByteCounterLogger implements ByteCounterListener
    {
        private static final Logger LOG = Log.getLogger(ByteCounterLogger.class);

        @Override
        public void onByteCount(ByteCountEvent event)
        {
            LOG.info("ByteCount [{}] Request=h:{}/b:{}/t:{}/a:{} Response=h:{}/b:{}/t:{}/a:{}",
                event.getRequest().getHttpURI(),
                event.getRequestCount().getHeaderCount(),
                event.getRequestCount().getBodyCount(),
                event.getRequestCount().getTrailerCount(),
                event.getRequestCount().getStreamAPICount(),
                event.getResponseCount().getHeaderCount(),
                event.getResponseCount().getBodyCount(),
                event.getResponseCount().getTrailerCount(),
                event.getResponseCount().getStreamAPICount()
            );
        }
    }
}
