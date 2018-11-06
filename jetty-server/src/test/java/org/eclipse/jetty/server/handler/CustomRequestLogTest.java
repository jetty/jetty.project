//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class CustomRequestLogTest
{
    Log _log;
    Server _server;
    LocalConnector _connector;


    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

    }

    void testHandlerServerStart(String formatString) throws Exception
    {
        _log = new Log(formatString);
        _server.setRequestLog(_log);
        _server.setHandler(new TestHandler());
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }


    @Test
    public void testQuery() throws Exception
    {
        testHandlerServerStart("clientIP: %a");

        _connector.getResponse("GET /foo?name=value HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }




    private class Log extends CustomRequestLog
    {
        public BlockingQueue<String> entries = new BlockingArrayQueue<>();

        public Log(String formatString)
        {
            super(formatString);
        }

        @Override
        protected boolean isEnabled()
        {
            return true;
        }

        @Override
        public void write(String requestEntry) throws IOException
        {
            try
            {
                entries.add(requestEntry);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private class TestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
        }
    }
}
