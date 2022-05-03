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

package org.eclipse.jetty.ee10.servlet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@Disabled
public class CustomRequestLogServletTest
{
    RequestLog _log;
    Server _server;
    LocalConnector _connector;
    BlockingQueue<String> _entries = new BlockingArrayQueue<>();
    String _tmpDir;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _tmpDir = new File(System.getProperty("java.io.tmpdir")).getCanonicalPath();
    }

    void testHandlerServerStart(String formatString) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/context");
        context.setBaseResource(Paths.get(_tmpDir));
        context.addServlet(TestServlet.class, "/servlet/*");

        TestRequestLogWriter writer = new TestRequestLogWriter();
        _log = new CustomRequestLog(writer, formatString);
        _server.setRequestLog(_log);
        _server.setHandler(context);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testLogFilename() throws Exception
    {
        testHandlerServerStart("Filename: %f");

        _connector.getResponse("GET /context/servlet/info HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        String expected = new File(_tmpDir + File.separator + "servlet" + File.separator + "info").getCanonicalPath();
        assertThat(log, is("Filename: " + expected));
    }

    @Test
    public void testLogRequestHandler() throws Exception
    {
        testHandlerServerStart("RequestHandler: %R");

        _connector.getResponse("GET /context/servlet/ HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, Matchers.containsString("TestServlet"));
    }

    class TestRequestLogWriter implements RequestLog.Writer
    {
        @Override
        public void write(String requestEntry)
        {
            try
            {
                _entries.add(requestEntry);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getRequestURI().contains("error404"))
            {
                response.setStatus(404);
            }
            else if (request.getRequestURI().contains("error301"))
            {
                response.setStatus(301);
            }
            else if (request.getHeader("echo") != null)
            {
                ServletOutputStream outputStream = response.getOutputStream();
                outputStream.print(request.getHeader("echo"));
            }
            else if (request.getRequestURI().contains("responseHeaders"))
            {
                response.addHeader("Header1", "value1");
                response.addHeader("Header2", "value2");
            }
        }
    }
}
