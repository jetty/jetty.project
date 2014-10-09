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

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.IO;
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
public class RequestLogHandlerTest
{
    private static final Logger LOG = Log.getLogger(RequestLogHandlerTest.class);

    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        public List<String> captured = new ArrayList<>();

        @Override
        public void log(Request request, Response response)
        {
            captured.add(String.format("%s %s %s %03d",request.getMethod(),request.getHttpURI().toString(),request.getProtocol(),response.getStatus()));
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
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.getWriter().print("Hello World");
            baseRequest.setHandled(true);
        }
    }
    
    private static class ResponseSendErrorHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.sendError(500, "Whoops");
            baseRequest.setHandled(true);
        }
    }
    
    private static class ServletExceptionHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new ServletException("Whoops");
        }
    }
    
    private static class IOExceptionHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new IOException("Whoops");
        }
    }
    
    private static class RuntimeExceptionHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new RuntimeException("Whoops");
        }
    }
    
    private static class AsyncOnTimeoutCompleteHandler extends AbstractTestHandler implements AsyncListener
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            AsyncContext ac = request.startAsync();
            ac.setTimeout(1000);
            ac.addListener(this);
            baseRequest.setHandled(true);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().complete();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }
    }
    
    private static class AsyncOnTimeoutCompleteUnhandledHandler extends AbstractTestHandler implements AsyncListener
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            AsyncContext ac = request.startAsync();
            ac.setTimeout(1000);
            ac.addListener(this);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().complete();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }
    }
    
    private static class AsyncOnTimeoutDispatchHandler extends AbstractTestHandler implements AsyncListener
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if(request.getAttribute("deep") == null)
            {
                AsyncContext ac = request.startAsync();
                ac.setTimeout(1000);
                ac.addListener(this);
                baseRequest.setHandled(true);
                request.setAttribute("deep",true);
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().dispatch();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }
    }
    
    private static class AsyncOnStartIOExceptionHandler extends AbstractTestHandler implements AsyncListener
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            AsyncContext ac = request.startAsync();
            ac.setTimeout(1000);
            ac.addListener(this);
            baseRequest.setHandled(true);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            event.getAsyncContext().complete();
            throw new IOException("Whoops");
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            LOG.warn("onError() -> {}",event);
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }
    }
    
    @Parameters(name="{0}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[] { new HelloHandler(), "/test", "GET /test HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutCompleteHandler(), "/test", "GET /test HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutCompleteUnhandledHandler(), "/test", "GET /test HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutDispatchHandler(), "/test", "GET /test HTTP/1.1 200" });
        
        data.add(new Object[] { new AsyncOnStartIOExceptionHandler(), "/test", "GET /test HTTP/1.1 500" });
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
    @Ignore
    public void testLogHandlerCollection() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
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
        ServerConnector connector = new ServerConnector(server);
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
        try (InputStream in = connection.getInputStream(); InputStreamReader reader = new InputStreamReader(in))
        {
            StringWriter writer = new StringWriter();
            IO.copy(reader,writer);
            return writer.toString();
        }
    }
}
