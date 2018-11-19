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
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class CustomRequestLogTest
{
    RequestLog _log;
    Server _server;
    LocalConnector _connector;
    BlockingQueue<String> _entries = new BlockingArrayQueue<>();


    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
    }

    void testHandlerServerStart(String formatString) throws Exception
    {
        TestRequestLogWriter writer = new TestRequestLogWriter();
        _log = new CustomRequestLog(writer, formatString);
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
    public void testModifier() throws Exception
    {
        testHandlerServerStart("%s: %!404,301{Referer}i");

        _connector.getResponse("GET /error404 HTTP/1.0\nReferer: testReferer\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("404: -\n"));

        _connector.getResponse("GET /error301 HTTP/1.0\nReferer: testReferer\n\n");
        log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("301: -\n"));

        _connector.getResponse("GET /success HTTP/1.0\nReferer: testReferer\n\n");
        log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("200: testReferer\n"));
    }

    @Test
    public void testInvalidArguments() throws Exception
    {
        fail();
    }


    @Test
    public void testDoublePercent() throws Exception
    {
        testHandlerServerStart("%%%%%%a");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("%%%a\n"));
    }

    @Test
    public void testLogClientIP() throws Exception
    {
        testHandlerServerStart("ClientIP: %a");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogConnectionIP() throws Exception
    {
        testHandlerServerStart("ConnectionIP: %{c}a");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogLocalIP() throws Exception
    {
        testHandlerServerStart("LocalIP: %A");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogResponseSize() throws Exception
    {
        testHandlerServerStart("ResponseSize: %B");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("ResponseSize: 0\n"));

        _connector.getResponse("GET / HTTP/1.0\nEcho: hello world\n\n");
        log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("ResponseSize: 11\n"));
    }

    @Test
    public void testLogResponseSizeCLF() throws Exception
    {
        testHandlerServerStart("ResponseSize: %b");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("ResponseSize: -\n"));

        _connector.getResponse("GET / HTTP/1.0\nEcho: hello world\n\n");
        log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("ResponseSize: 11\n"));
    }

    @Test
    public void testLogRequestCookie() throws Exception
    {
        testHandlerServerStart("RequestCookie: %{cookieName}C, %{cookie2}C, %{cookie3}C");

        _connector.getResponse("GET / HTTP/1.0\nCookie: cookieName=cookieValue; cookie2=value2\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("RequestCookies: cookieValue, value2, -\n"));
    }

    @Test
    public void testLogRequestCookies() throws Exception
    {
        testHandlerServerStart("RequestCookies: %C");

        _connector.getResponse("GET / HTTP/1.0\nCookie: cookieName=cookieValue; cookie2=value2\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("RequestCookies: cookieName=cookieValue;cookie2=value2\n"));
    }

    @Test
    public void testLogEnvironmentVar() throws Exception
    {
        testHandlerServerStart("EnvironmentVar: %{JAVA_HOME}e");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("EnvironmentVar: " + System.getenv("JAVA_HOME") + "\n"));
    }

    @Test
    public void testLogFilename() throws Exception
    {
        testHandlerServerStart("Filename: %f");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRemoteHostName() throws Exception
    {
        testHandlerServerStart("RemoteHostName: %h");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRequestProtocol() throws Exception
    {
        testHandlerServerStart("RequestProtocol: %H");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("Protocol: HTTP/1.0\n"));
    }

    @Test
    public void testLogRequestHeader() throws Exception
    {
        testHandlerServerStart("RequestHeader: %{Header1}i, %{Header2}i, %{Header3}i");

        _connector.getResponse("GET / HTTP/1.0\nHeader1: value1\nHeader2: value2\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("RequestHeader: value1, value2, -\n"));
    }

    @Test
    public void testLogKeepAliveRequests() throws Exception
    {
        testHandlerServerStart("KeepAliveRequests: %k");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        _connector.getResponse("GET / HTTP/1.0\n\n");
        _connector.getResponse("GET / HTTP/1.0\n\n");

        _entries.poll(5,TimeUnit.SECONDS);
        _entries.poll(5,TimeUnit.SECONDS);
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRequestMethod() throws Exception
    {
        testHandlerServerStart("RequestMethod: %m");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("RequestMethod: GET\n"));
    }

    @Test
    public void testLogResponseHeader() throws Exception
    {
        testHandlerServerStart("ResponseHeader: %{Header1}o, %{Header2}o, %{Header3}o");

        _connector.getResponse("GET /responseHeaders HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("ResponseHeader: value1, value2, -\n"));
    }

    @Test
    public void testLogCanonicalPort() throws Exception
    {
        testHandlerServerStart("CanonicalPort: %p, %{canonical}p");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogLocalPort() throws Exception
    {
        testHandlerServerStart("LocalPort: %{local}p");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRemotePort() throws Exception
    {
        testHandlerServerStart("RemotePort: %{remote}p");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogUnknownPort() throws Exception
    {
        assertThrows(IllegalArgumentException.class, ()->
        {
            testHandlerServerStart("%{unknown}p");
        });
    }

    @Test
    public void testLogQueryString() throws Exception
    {
        testHandlerServerStart("QueryString: %q");

        _connector.getResponse("GET /path?queryString HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("QueryString: ?queryString\n"));
    }

    @Test
    public void testLogRequestFirstLine() throws Exception
    {
        testHandlerServerStart("RequestFirstLin: %r");

        _connector.getResponse("GET /path?query HTTP/1.0\nHeader: null\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("RequestFirstLin: GET /path?query HTTP/1.0\n"));
    }

    @Test
    public void testLogRequestHandler() throws Exception
    {
        testHandlerServerStart("RequestHandler: %R");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogResponseStatus() throws Exception
    {
        testHandlerServerStart("LogResponseStatus: %s");

        _connector.getResponse("GET /error404 HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 404\n"));

        _connector.getResponse("GET /error301 HTTP/1.0\n\n");
        log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 301\n"));

        _connector.getResponse("GET / HTTP/1.0\n\n");
        log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 200\n"));
    }

    @Test
    public void testLogRequestTime() throws Exception
    {
        testHandlerServerStart("RequestTime: %t");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRequestTimeCustomFormats() throws Exception
    {
        /*
        The time, in the form given by format, which should be in an extended strftime(3) format (potentially localized).
        If the format starts with begin: (default) the time is taken at the beginning of the request processing.
        If it starts with end: it is the time when the log entry gets written, close to the end of the request processing.

        In addition to the formats supported by strftime(3), the following format tokens are supported:
            sec         number of seconds since the Epoch
            msec	    number of milliseconds since the Epoch
            usec	    number of microseconds since the Epoch
            msec_frac  	millisecond fraction
            usec_frac	microsecond fraction

        These tokens can not be combined with each other or strftime(3) formatting in the same format string.
        You can use multiple %{format}t tokens instead.
        */

        testHandlerServerStart("RequestTime: %{?}t");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogLatencyMicroseconds() throws Exception
    {
        testHandlerServerStart("LatencyMicroseconds: %{us}Tus");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogLatencyMilliseconds() throws Exception
    {
        testHandlerServerStart("LatencyMilliseconds: %{ms}Tms");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogLatencySeconds() throws Exception
    {
        testHandlerServerStart("LatencySeconds: %{s}Ts");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRequestAuthentication() throws Exception
    {
        testHandlerServerStart("RequestAuthentication: %u");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogUrlRequestPath() throws Exception
    {
        testHandlerServerStart("UrlRequestPath: %U");

        _connector.getResponse("GET /path?query HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        assertThat(log, is("UrlRequestPath: /path\n"));
    }

    @Test
    public void testLogServerName() throws Exception
    {
        testHandlerServerStart("ServerName: %v");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogConnectionStatus() throws Exception
    {
        testHandlerServerStart("ConnectionStatus: %X");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogBytesReceived() throws Exception
    {
        testHandlerServerStart("BytesReceived: %I");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogBytesSent() throws Exception
    {
        testHandlerServerStart("BytesSent: %I");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogBytesTransferred() throws Exception
    {
        testHandlerServerStart("BytesTransferred: %I");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogRequestTrailer() throws Exception
    {
        testHandlerServerStart("RequestTrailer: %{trailerName}ti");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
    }

    @Test
    public void testLogResponseTrailer() throws Exception
    {
        testHandlerServerStart("ResponseTrailer: %{trailerName}to");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5,TimeUnit.SECONDS);
        fail(log);
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

            baseRequest.setHandled(true);
        }
    }
}
