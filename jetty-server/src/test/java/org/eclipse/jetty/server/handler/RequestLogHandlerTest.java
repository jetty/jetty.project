//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for RequestLogHandler behavior.
 * <p>
 * Tests different request handler behavior against different server+error configurations
 */
@RunWith(Parameterized.class)
@Ignore
public class RequestLogHandlerTest
{
    private static final Logger LOG = Log.getLogger(RequestLogHandlerTest.class);

    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        public List<String> captured = new ArrayList<>();

        @Override
        public void log(Request request, Response response)
        {
            int status = response.getCommittedMetaData().getStatus();
            captured.add(String.format("%s %s %s %03d",request.getMethod(),request.getRequestURI(),request.getProtocol(),status));
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
            response.sendError(500,"Whoops");
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
            if (request.getAttribute("deep") == null)
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

    public static class OKErrorHandler extends ErrorHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (baseRequest.isHandled() || response.isCommitted())
            {
                return;
            }

            // collect error details
            String reason = (response instanceof Response)?((Response)response).getReason():null;
            int status = response.getStatus();

            // intentionally set response status to OK (this is a test to see what is actually logged)
            response.setStatus(200);
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("Error %d: %s%n",status,reason);
            baseRequest.setHandled(true);
        }
    }

    public static class DispatchErrorHandler extends ErrorHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (baseRequest.isHandled() || response.isCommitted())
            {
                return;
            }

            RequestDispatcher dispatcher = request.getRequestDispatcher("/errorok/");
            assertThat("Dispatcher", dispatcher, notNullValue());

            try
            {
                dispatcher.forward(request,response);
            }
            catch (ServletException e)
            {
                throw new IOException("Dispatch.forward failed",e);
            }
        }
    }

    public static class AltErrorHandler extends ErrorHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (baseRequest.isHandled() || response.isCommitted())
            {
                return;
            }

            // collect error details
            String reason = (response instanceof Response)?((Response)response).getReason():null;
            int status = response.getStatus();

            // intentionally set response status to OK (this is a test to see what is actually logged)
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("Error %d: %s%n",status,reason);
            baseRequest.setHandled(true);
        }
    }

    @Parameters(name = "{0}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[] { new HelloHandler(), "/test/", "GET /test/ HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutCompleteHandler(), "/test/", "GET /test/ HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutCompleteUnhandledHandler(), "/test/", "GET /test/ HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutDispatchHandler(), "/test/", "GET /test/ HTTP/1.1 200" });

        data.add(new Object[] { new AsyncOnStartIOExceptionHandler(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new ResponseSendErrorHandler(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new ServletExceptionHandler(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new IOExceptionHandler(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new RuntimeExceptionHandler(), "/test/", "GET /test/ HTTP/1.1 500" });

        return data;
    }

    @Parameter(0)
    public Handler testHandler;

    @Parameter(1)
    public String requestPath;

    @Parameter(2)
    public String expectedLogEntry;

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection. all other configuration on server at defaults.
     * @throws Exception if test failure
     */
    @Test(timeout = 4000)
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

    @Test(timeout = 4000)
    public void testMultipleLogHandlers() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[]{connector});

        List<CaptureLog> captureLogs = new ArrayList<>();
        List<Handler> handlerList = new ArrayList<>();
        handlerList.add(testHandler);

        for (int i = 0; i < 4; ++i) {
            CaptureLog captureLog = new CaptureLog();
            captureLogs.add(captureLog);
            RequestLogHandler requestLog = new RequestLogHandler();
            requestLog.setRequestLog(captureLog);
            handlerList.add(requestLog);
        }

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(handlerList.toArray(new Handler[0]));
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

            for (CaptureLog captureLog:captureLogs)
                assertRequestLog(captureLog);
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection and also with the default ErrorHandler as server bean in place.
     * @throws Exception if test failure
     */
    @Test(timeout = 4000)
    public void testLogHandlerCollection_ErrorHandler_ServerBean() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });

        ErrorHandler errorHandler = new ErrorHandler();
        server.addBean(errorHandler);

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

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection and also with the ErrorHandler in place.
     * @throws Exception if test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerCollection_AltErrorHandler() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        
        AltErrorHandler errorDispatcher = new AltErrorHandler();
        server.addBean(errorDispatcher);
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler errorContext = new ContextHandler("/errorpage");
        errorContext.setHandler(new AltErrorHandler());
        ContextHandler testContext = new ContextHandler("/test");
        testContext.setHandler(testHandler);
        contexts.addHandler(errorContext);
        contexts.addHandler(testContext);

        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { contexts, requestLog });
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
    
    /**
     * Test a RequestLogHandler at the end of a HandlerCollection and also with the ErrorHandler in place.
     * @throws Exception if test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerCollection_OKErrorHandler() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        
        OKErrorHandler errorDispatcher = new OKErrorHandler();
        server.addBean(errorDispatcher);
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler errorContext = new ContextHandler("/errorpage");
        errorContext.setHandler(new AltErrorHandler());
        ContextHandler testContext = new ContextHandler("/test");
        testContext.setHandler(testHandler);
        contexts.addHandler(errorContext);
        contexts.addHandler(testContext);

        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { contexts, requestLog });
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
    
    /**
     * Test a RequestLogHandler at the end of a HandlerCollection and also with the ErrorHandler in place.
     * @throws Exception if test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerCollection_DispatchErrorHandler() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        
        DispatchErrorHandler errorDispatcher = new DispatchErrorHandler();
        server.addBean(errorDispatcher);
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        ContextHandler errorContext = new ContextHandler("/errorok");
        errorContext.setHandler(new OKErrorHandler());
        ContextHandler testContext = new ContextHandler("/test");
        testContext.setHandler(testHandler);
        contexts.addHandler(errorContext);
        contexts.addHandler(testContext);

        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { contexts, requestLog });
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

    @Test(timeout = 4000)
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
        
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class,HttpChannelState.class))
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
