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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.security.Credential;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class CustomRequestLogTest
{
    private final BlockingQueue<String> _logs = new BlockingArrayQueue<>();

    private Server _server;
    private LocalConnector _connector;
    private Path _baseDir;

    private void start(String formatString, HttpServlet servlet) throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        TestRequestLogWriter writer = new TestRequestLogWriter();
        RequestLog requestLog = new CustomRequestLog(writer, formatString);
        _server.setRequestLog(requestLog);

        Files.createDirectory(_baseDir.resolve("servlet"));
        Files.createFile(_baseDir.resolve("servlet/info"));
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setBaseResourceAsPath(_baseDir);
        context.setContextPath("/context");
        context.addServlet(new ServletHolder(servlet), "/servlet/*");

        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("username", Credential.getCredential("password"), new String[]{"user"});
        loginService.setUserStore(userStore);
        loginService.setName("realm");

        Constraint constraint = new Constraint.Builder()
            .name("auth")
            .authorization(Constraint.Authorization.ANY_USER).build();

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secure/*");
        mapping.setConstraint(constraint);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.addConstraintMapping(mapping);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        context.setSecurityHandler(security);

        _server.setHandler(context);

        _server.start();
    }

    @AfterEach
    public void after()
    {
        LifeCycle.stop(_server);
    }

    @BeforeEach
    public void setup(WorkDir workDir)
    {
        _baseDir = workDir.getEmptyPathDir();
    }

    @Test
    public void testLogFilename() throws Exception
    {
        start("Filename: %f", new SimpleServlet());

        _connector.getResponse("GET /context/servlet/info HTTP/1.0\n\n");
        String log = _logs.poll(5, TimeUnit.SECONDS);
        String expected = _baseDir.resolve("servlet/info").toString();
        assertThat(log, is("Filename: " + expected));
    }

    @Test
    public void testLogRequestHandler() throws Exception
    {
        start("RequestHandler: %R", new SimpleServlet());

        _connector.getResponse("GET /context/servlet/ HTTP/1.0\n\n");
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, Matchers.containsString(SimpleServlet.class.getSimpleName()));
    }

    @Test
    public void testLogRemoteUser() throws Exception
    {
        String authHeader = HttpHeader.AUTHORIZATION + ": Basic " + Base64.getEncoder().encodeToString("username:password".getBytes());
        start("%u", new SimpleServlet());

        _connector.getResponse("GET /context/servlet/unsecure HTTP/1.0\n\n");
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("-"));

        _connector.getResponse("GET /context/servlet/secure HTTP/1.0\n" + authHeader + "\n\n");
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("username"));
    }

    private class TestRequestLogWriter implements RequestLog.Writer
    {
        @Override
        public void write(String requestEntry)
        {
            _logs.add(requestEntry);
        }
    }

    private static class SimpleServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            // Trigger the authentication.
            request.getRemoteUser();
        }
    }
}
