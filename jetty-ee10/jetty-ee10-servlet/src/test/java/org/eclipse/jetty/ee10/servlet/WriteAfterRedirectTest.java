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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ValidatingConnectionPool;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WriteAfterRedirectTest
{
    private Server _server;
    private URI _uri;
    private HttpClient _client;

    public void startServer(HttpServlet servlet) throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        _server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(servlet, "/*");
        _server.setHandler(context);
        _server.start();
        _uri = URI.create("http://localhost:" + connector.getLocalPort() + "/");

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testWriteAfterRedirect() throws Exception
    {
        AtomicReference<Throwable> errorReference = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String pathInContext = URIUtil.addPaths(req.getServletPath(), req.getPathInfo());
                if (pathInContext.startsWith("/redirect"))
                {
                    OutputStream out = resp.getOutputStream();
                    resp.sendRedirect("/hello");
                    try
                    {
                        out.write('x');
                    }
                    catch (Throwable t)
                    {
                        errorReference.set(t);
                        latch.countDown();
                        throw t;
                    }
                }
                else
                {
                    PrintWriter writer = resp.getWriter();
                    writer.print("hello world");
                }
            }
        });

        // The server will close the connection without any indication to the client,
        // so when the client follows the redirect it may send it on a closed connection,
        // which will fail the test. ValidatingConnectionPool is designed for these cases.
        _client.getTransport().setConnectionPoolFactory(destination ->
            new ValidatingConnectionPool(destination, _client.getMaxConnectionsPerDestination(), _client.getScheduler(), 1000)
        );

        // We get the correct redirect.
        _client.setFollowRedirects(false);
        ContentResponse response = _client.GET(_uri.resolve("redirect"));
        assertThat(response.getStatus(), is(HttpServletResponse.SC_MOVED_TEMPORARILY));
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_LENGTH), is("0"));
        assertThat(response.getContent().length, is(0));
        assertThat(response.getHeaders().get(HttpHeader.LOCATION), is("/hello"));

        // Following the redirect gives the hello page.
        _client.setFollowRedirects(true);
        response = _client.GET(_uri.resolve("redirect"));
        assertThat(response.getStatus(), is(HttpServletResponse.SC_OK));
        assertThat(response.getContentAsString(), equalTo("hello world"));

        // The write() in the servlet actually threw because the HttpOutput was closed.
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(errorReference.get(), instanceOf(IOException.class));
        assertThat(errorReference.get().getMessage(), containsString("Closed"));
    }
}
