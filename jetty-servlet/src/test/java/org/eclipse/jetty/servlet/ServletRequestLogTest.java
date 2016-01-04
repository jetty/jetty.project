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

package org.eclipse.jetty.servlet;

import static org.hamcrest.Matchers.is;
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
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
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

/**
 * Servlet equivalent of the jetty-server's RequestLogHandlerTest, but with more ErrorHandler twists. 
 */
@RunWith(Parameterized.class)
@Ignore
public class ServletRequestLogTest
{
    private static final Logger LOG = Log.getLogger(ServletRequestLogTest.class);

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
    
    @SuppressWarnings("serial")
    private static abstract class AbstractTestServlet extends HttpServlet
    {
        @Override
        public String toString()
        {
            return this.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("serial")
    private static class HelloServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/plain");
            response.getWriter().print("Hello World");
        }
    }
    
    @SuppressWarnings("serial")
    private static class ResponseSendErrorServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.sendError(500, "Whoops");
        }
    }
    
    @SuppressWarnings("serial")
    private static class ServletExceptionServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            throw new ServletException("Whoops");
        }
    }
    
    @SuppressWarnings("serial")
    private static class IOExceptionServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            throw new IOException("Whoops");
        }
    }
    
    @SuppressWarnings("serial")
    private static class RuntimeExceptionServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            throw new RuntimeException("Whoops");
        }
    }
    
    @SuppressWarnings("serial")
    private static class AsyncOnTimeoutCompleteServlet extends AbstractTestServlet implements AsyncListener
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
    
    @SuppressWarnings("serial")
    private static class AsyncOnTimeoutDispatchServlet extends AbstractTestServlet implements AsyncListener
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if(request.getAttribute("deep") == null)
            {
                AsyncContext ac = request.startAsync();
                ac.setTimeout(1000);
                ac.addListener(this);
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
    
    @SuppressWarnings("serial")
    private static class AsyncOnStartIOExceptionServlet extends AbstractTestServlet implements AsyncListener
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            AsyncContext ac = request.startAsync();
            ac.setTimeout(1000);
            ac.addListener(this);
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
    
    @SuppressWarnings("serial")
    public static class CustomErrorServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            // collect error details
            String reason = (response instanceof Response)?((Response)response).getReason():null;
            int status = response.getStatus();

            // intentionally set response status to OK (this is a test to see what is actually logged)
            response.setStatus(200);
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("Error %d: %s%n",status,reason);
        }
    }
    
    @Parameters(name="{0}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[] { new HelloServlet(), "/test/", "GET /test/ HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutCompleteServlet(), "/test/", "GET /test/ HTTP/1.1 200" });
        data.add(new Object[] { new AsyncOnTimeoutDispatchServlet(), "/test/", "GET /test/ HTTP/1.1 200" });
        
        data.add(new Object[] { new AsyncOnStartIOExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new ResponseSendErrorServlet(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new ServletExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new IOExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500" });
        data.add(new Object[] { new RuntimeExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500" });

        return data;
    }

    @Parameter(0)
    public Servlet testServlet;
    
    @Parameter(1)
    public String requestPath;

    @Parameter(2)
    public String expectedLogEntry;

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection.
     * This handler chain is setup to look like Jetty versions up to 9.2. 
     * Default configuration.
     * @throws Exception on test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerCollection() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });

        // First the behavior as defined in etc/jetty.xml
        // id="Handlers"
        HandlerCollection handlers = new HandlerCollection();
        // id="Contexts"
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        // id="DefaultHandler"
        DefaultHandler defaultHandler = new DefaultHandler();
        
        handlers.setHandlers(new Handler[] { contexts, defaultHandler });
        server.setHandler(handlers);

        // Next the behavior as defined by etc/jetty-requestlog.xml
        // the id="RequestLog"
        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);

        handlers.addHandler(requestLog);
        
        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app);
        
        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder,"/test");

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
     * Test a RequestLogHandler at the end of a HandlerCollection.
     * and also with the default ErrorHandler as server bean in place.
     * @throws Exception on test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerCollection_ErrorHandler_ServerBean() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        
        ErrorHandler errorHandler = new ErrorHandler();
        server.addBean(errorHandler);

        // First the behavior as defined in etc/jetty.xml
        // id="Handlers"
        HandlerCollection handlers = new HandlerCollection();
        // id="Contexts"
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        // id="DefaultHandler"
        DefaultHandler defaultHandler = new DefaultHandler();
        
        handlers.setHandlers(new Handler[] { contexts, defaultHandler });
        server.setHandler(handlers);

        // Next the behavior as defined by etc/jetty-requestlog.xml
        // the id="RequestLog"
        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);

        handlers.addHandler(requestLog);
        
        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app);
        
        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder,"/test");

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
     * Test a RequestLogHandler at the end of a HandlerCollection
     * using servlet specific error page mapping.
     * @throws Exception on test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerCollection_SimpleErrorPageMapping() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        
        // First the behavior as defined in etc/jetty.xml
        // id="Handlers"
        HandlerCollection handlers = new HandlerCollection();
        // id="Contexts"
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        // id="DefaultHandler"
        DefaultHandler defaultHandler = new DefaultHandler();
        
        handlers.setHandlers(new Handler[] { contexts, defaultHandler });
        server.setHandler(handlers);

        // Next the behavior as defined by etc/jetty-requestlog.xml
        // the id="RequestLog"
        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);

        handlers.addHandler(requestLog);
        
        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app);
        
        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder,"/test");
        app.addServlet(CustomErrorServlet.class,"/errorpage");
        
        // Add error page mapping
        ErrorPageErrorHandler errorMapper = new ErrorPageErrorHandler();
        errorMapper.addErrorPage(500,"/errorpage");
        app.setErrorHandler(errorMapper);

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
     * Test an alternate (proposed) setup for using RequestLogHandler in a wrapped style
     * @throws Exception on test failure
     */
    @Test(timeout=4000)
    public void testLogHandlerWrapped() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });

        // First the behavior as defined in etc/jetty.xml (as is)
        // id="Handlers"
        HandlerCollection handlers = new HandlerCollection();
        // id="Contexts"
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        // id="DefaultHandler"
        DefaultHandler defaultHandler = new DefaultHandler();
        
        handlers.setHandlers(new Handler[] { contexts, defaultHandler });
        server.setHandler(handlers);

        // Next the proposed behavioral change to etc/jetty-requestlog.xml
        // the id="RequestLog"
        RequestLogHandler requestLog = new RequestLogHandler();
        CaptureLog captureLog = new CaptureLog();
        requestLog.setRequestLog(captureLog);
        
        Handler origServerHandler = server.getHandler();
        requestLog.setHandler(origServerHandler);
        server.setHandler(requestLog);
        
        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app);
        
        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder,"/test");
        app.addServlet(CustomErrorServlet.class,"/errorpage");
        
        // Add error page mapping
        ErrorPageErrorHandler errorMapper = new ErrorPageErrorHandler();
        errorMapper.addErrorPage(500,"/errorpage");
        app.setErrorHandler(errorMapper);

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
