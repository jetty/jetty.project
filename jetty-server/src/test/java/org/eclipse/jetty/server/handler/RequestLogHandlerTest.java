//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.Servlet3Continuation;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
@Ignore
public class RequestLogHandlerTest
{
    private static final Logger LOG = Log.getLogger(RequestLogHandlerTest.class);

    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        public List<String> captured = new ArrayList<String>();

        public void log(Request request, Response response)
        {
            captured.add(String.format("%s %s %s %03d",request.getMethod(),request.getUri().toString(),request.getProtocol(),response.getStatus()));
        }
    }
    
    private static abstract class AbstractTestHandler extends AbstractHandler
    {
        @Override
        public String toString()
        {
            return this.getClass().getSimpleName();
        }
    }

    private static class HelloHandler extends AbstractTestHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.getWriter().print("Hello World");
            baseRequest.setHandled(true);
        }
    }
    
    private static class ResponseSendErrorHandler extends AbstractTestHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.sendError(500, "Whoops");
            baseRequest.setHandled(true);
        }
    }
    
    private static class ServletExceptionHandler extends AbstractTestHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new ServletException("Whoops");
        }
    }
    
    private static class IOExceptionHandler extends AbstractTestHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new IOException("Whoops");
        }
    }
    
    private static class RuntimeExceptionHandler extends AbstractTestHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new RuntimeException("Whoops");
        }
    }
    
    private static class ContinuationOnTimeoutCompleteHandler extends AbstractTestHandler implements ContinuationListener
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Continuation ac = new Servlet3Continuation(request);
            ac.setTimeout(1000);
            ac.addContinuationListener(this);
            baseRequest.setHandled(true);
        }

        public void onComplete(Continuation continuation)
        {
        }

        public void onTimeout(Continuation continuation)
        {
            continuation.complete();
        }
    }
    
    private static class ContinuationOnTimeoutCompleteUnhandledHandler extends AbstractTestHandler implements ContinuationListener
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Continuation ac = new Servlet3Continuation(request);
            ac.setTimeout(1000);
            ac.addContinuationListener(this);
        }
        
        public void onComplete(Continuation continuation)
        {
        }

        public void onTimeout(Continuation continuation)
        {
            continuation.complete();
        }
    }
    
    private static class ContinuationOnTimeoutRuntimeExceptionHandler extends AbstractTestHandler implements ContinuationListener
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Continuation ac = new Servlet3Continuation(request);
            ac.setTimeout(1000);
            ac.addContinuationListener(this);
            baseRequest.setHandled(true);
        }

        public void onComplete(Continuation continuation)
        {
        }

        public void onTimeout(Continuation continuation)
        {
            throw new RuntimeException("Ooops");
        }
    }
    
    @Parameters(name="{0}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<Object[]>();

        data.add(new Object[] { new HelloHandler(), "/test", "GET /test HTTP/1.1 200" });
        data.add(new Object[] { new ContinuationOnTimeoutCompleteHandler(), "/test", "GET /test HTTP/1.1 200" });
        data.add(new Object[] { new ContinuationOnTimeoutCompleteUnhandledHandler(), "/test", "GET /test HTTP/1.1 200" });
        
        data.add(new Object[] { new ContinuationOnTimeoutRuntimeExceptionHandler(), "/test", "GET /test HTTP/1.1 500" });
        data.add(new Object[] { new ResponseSendErrorHandler(), "/test", "GET /test HTTP/1.1 500" });
        data.add(new Object[] { new ServletExceptionHandler(), "/test", "GET /test HTTP/1.1 500" });
        data.add(new Object[] { new IOExceptionHandler(), "/test", "GET /test HTTP/1.1 500" });
        data.add(new Object[] { new RuntimeExceptionHandler(), "/test", "GET /test HTTP/1.1 500" });

        return data;
    }

    @Parameter(0)
    public Handler testHandler;
    
    @Parameter(1)
    public String requestPath;

    @Parameter(2)
    public String expectedLogEntry;

    @Test(timeout=4000)
    public void testLogHandlerCollection() throws Exception
    {
        Server server = new Server();
        
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });

        CaptureLog captureLog = new CaptureLog();

        RequestLogHandler requestLog = new RequestLogHandler();
        requestLog.setRequestLog(captureLog);

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { testHandler, requestLog });
        server.setHandler(handlers);

        try
        {
            server.start();

            String host = connector.getHost();
            if (host == null)
            {
                host = "localhost";
            }
            int port = connector.getLocalPort();

            URI serverUri = new URI("http",null,host,port,requestPath,null,null);

            // Make call to test handler
            HttpURLConnection connection = (HttpURLConnection)serverUri.toURL().openConnection();
            try
            {
                connection.setAllowUserInteraction(false);

                // log response status code
                int statusCode = connection.getResponseCode();
                LOG.debug("Response Status Code: {}",statusCode);

                if (statusCode == 200)
                {
                    // collect response message and log it
                    String content = getResponseContent(connection);
                    LOG.debug("Response Content: {}",content);
                }
            }
            finally
            {
                connection.disconnect();
            }

            assertRequestLog(captureLog);
        }
        finally
        {
            server.stop();
        }
    }
    
    @Test(timeout=4000)
    public void testLogHandlerWrapped() throws Exception
    {
        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });

        CaptureLog captureLog = new CaptureLog();

        RequestLogHandler requestLog = new RequestLogHandler();
        requestLog.setRequestLog(captureLog);
        
        requestLog.setHandler(testHandler);

        server.setHandler(requestLog);

        try
        {
            server.start();

            String host = connector.getHost();
            if (host == null)
            {
                host = "localhost";
            }
            int port = connector.getLocalPort();

            URI serverUri = new URI("http",null,host,port,"/test",null,null);

            // Make call to test handler
            HttpURLConnection connection = (HttpURLConnection)serverUri.toURL().openConnection();
            try
            {
                connection.setAllowUserInteraction(false);

                // log response status code
                int statusCode = connection.getResponseCode();
                LOG.info("Response Status Code: {}",statusCode);

                if (statusCode == 200)
                {
                    // collect response message and log it
                    String content = getResponseContent(connection);
                    LOG.info("Response Content: {}",content);
                }
            }
            finally
            {
                connection.disconnect();
            }

            assertRequestLog(captureLog);
        }
        finally
        {
            server.stop();
        }
    }

    private void assertRequestLog(CaptureLog captureLog)
    {
        int captureCount = captureLog.captured.size();

        if (captureCount != 1)
        {
            LOG.warn("Capture Log size is {}, expected to be 1",captureCount);
            if (captureCount > 1)
            {
                for (int i = 0; i < captureCount; i++)
                {
                    LOG.warn("[{}] {}",i,captureLog.captured.get(i));
                }
            }
            assertThat("Capture Log Entry Count",captureLog.captured.size(),is(1));
        }

        String actual = captureLog.captured.get(0);
        assertThat("Capture Log",actual,is(expectedLogEntry));
    }

    private String getResponseContent(HttpURLConnection connection) throws IOException
    {
        InputStream in = null;
        InputStreamReader reader = null;
        
        try
        {
            in = connection.getInputStream();
            reader = new InputStreamReader(in,StringUtil.__UTF8_CHARSET);
            StringWriter writer = new StringWriter();
            IO.copy(reader,writer);
            return writer.toString();
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }
}