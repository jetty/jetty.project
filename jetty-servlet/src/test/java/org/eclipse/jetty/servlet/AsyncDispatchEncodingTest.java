//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlet;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AsyncDispatchEncodingTest
{
    private Server server;
    private LocalConnector localConnector;

    public Server startServer(ServletContextHandler servletContextHandler) throws Exception
    {
        server = new Server();

        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(servletContextHandler);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);
        server.start();
        return server;
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    /**
     * An AsyncContext.dispatch(String) test where the incoming query from the Client has bad URI encoding.
     * The merging of query should fail the request and result in a 400 response
     */
    @Test
    public void testAsyncDispatchBadClientQuery() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        ServletHolder asyncHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                AsyncContext asyncContext = req.startAsync();
                // dispatch to path with valid URI encoding
                asyncContext.dispatch("/simple/?msg=Greetings%20Earthling");
            }
        });
        asyncHolder.setAsyncSupported(true);
        contextHandler.addServlet(asyncHolder, "/dispatch/");

        ServletHolder simpleHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                // we should never reach this.
                resp.setStatus(444);
            }
        });
        simpleHolder.setAsyncSupported(true);
        // Add valid endpoint to satisfy AsyncContext.dispatch(String) API contract
        contextHandler.addServlet(simpleHolder, "/simple/*");

        startServer(contextHandler);

        // Create request with bad query encoding (trigger UTF-8 error)
        StringBuilder rawReq = new StringBuilder();
        rawReq.append("GET /dispatch/?search=%E8. HTTP/1.1\r\n");
        rawReq.append("Host: local\r\n");
        rawReq.append("Connection: close\r\n");
        rawReq.append("\r\n");

        String rawResp = localConnector.getResponse(rawReq.toString());
        HttpTester.Response resp = HttpTester.parseResponse(rawResp);
        // Since this is a User-Agent bug, this should be 400 status response.
        assertThat("Response.status", resp.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    /**
     * An AsyncContext.dispatch(String) test where the incoming query from the Client is sane,
     * but the application provided path has a query with bad URI encoding.
     * The merging of query should fail the request and result in a 500 response.
     */
    @Test
    public void testAsyncDispatchBadAppQuery() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        ServletHolder asyncHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                AsyncContext asyncContext = req.startAsync();
                // dispatch to path with invalid URI encoding
                asyncContext.dispatch("/simple/?msg=%E8.");
            }
        });
        asyncHolder.setAsyncSupported(true);
        contextHandler.addServlet(asyncHolder, "/dispatch/");

        ServletHolder simpleHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                // we should never reach this
                resp.setStatus(444);
            }
        });
        simpleHolder.setAsyncSupported(true);
        // Add valid endpoint to satisfy AsyncContext.dispatch(String) API contract
        contextHandler.addServlet(simpleHolder, "/simple/*");

        startServer(contextHandler);

        // Create request with valid query encoding
        StringBuilder rawReq = new StringBuilder();
        rawReq.append("GET /dispatch/?search=Invitation%20Sent HTTP/1.1\r\n");
        rawReq.append("Host: local\r\n");
        rawReq.append("Connection: close\r\n");
        rawReq.append("\r\n");

        String rawResp = localConnector.getResponse(rawReq.toString());
        HttpTester.Response resp = HttpTester.parseResponse(rawResp);
        // Since this is an Application bug, this should be 500 status response.
        assertThat("Response.status", resp.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }
}
