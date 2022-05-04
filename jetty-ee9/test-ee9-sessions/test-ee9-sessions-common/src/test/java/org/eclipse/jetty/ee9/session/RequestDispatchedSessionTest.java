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

package org.eclipse.jetty.ee9.session;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RequestDispatchedSessionTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Default session behavior
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(LoginServlet.class, "/login");
        contextHandler.addServlet(ShowUserServlet.class, "/user");
        contextHandler.addServlet(DefaultServlet.class, "/");

        Handler.Collection list = new Handler.Collection();
        list.addHandler(contextHandler);
        list.addHandler(new DefaultHandler());

        server.start();
    }

    @AfterEach
    public void stopServerAndClient()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @Test
    public void testRedirect() throws Exception
    {
        Fields postForm = new Fields();
        postForm.add("username", "whizbat");

        ContentResponse response = client.newRequest(server.getURI().resolve("/login"))
            .method(HttpMethod.POST)
            .body(new FormRequestContent(postForm))
            .send();
        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));
    }

    public static class LoginServlet extends HttpServlet
    {
        public static final String USERNAME = "loggedInUserName";

        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getParameter("username") != null)
            {
                if (request.getSession() != null)
                {
                    request.getSession().invalidate();
                }
                request.getSession(true).setAttribute(USERNAME, request.getParameter("username"));
                request.getRequestDispatcher("/user").forward(request, response);
            }
        }
    }

    public static class ShowUserServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            showUser(req, resp);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            showUser(req, resp);
        }

        private void showUser(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            PrintWriter out = resp.getWriter();
            String userName = (String)req.getSession().getAttribute(LoginServlet.USERNAME);
            out.printf("UserName is %s%n", userName);
        }
    }
}
