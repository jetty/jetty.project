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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.ErrorHandler;
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.nested.Response;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Servlet equivalent of the jetty-server's RequestLogHandlerTest, but with more ErrorHandler twists.
 */
public class ServletRequestLogTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletRequestLogTest.class);

    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        ServletRequest _request;
        public List<String> captured = new ArrayList<>();

        @Override
        public void log(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response)
        {
            int status = response.getStatus();
            captured.add(String.format("%s %s %s %03d", request.getMethod(), request.getHttpURI().asString(), request.getHttpURI().getScheme(), status));
        }
    }

    @SuppressWarnings("serial")
    private abstract static class AbstractTestServlet extends HttpServlet
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
            response.sendError(500, "FromResponseSendErrorServlet");
        }
    }

    @SuppressWarnings("serial")
    private static class ServletExceptionServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            throw new ServletException("FromServletExceptionServlet");
        }
    }

    @SuppressWarnings("serial")
    private static class IOExceptionServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            throw new IOException("FromIOExceptionServlet");
        }
    }

    @SuppressWarnings("serial")
    private static class RuntimeExceptionServlet extends AbstractTestServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            throw new RuntimeException("FromRuntimeExceptionServlet");
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
            if (request.getAttribute("deep") == null)
            {
                AsyncContext ac = request.startAsync();
                ac.setTimeout(1000);
                ac.addListener(this);
                request.setAttribute("deep", true);
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
            LOG.warn("onError() -> {}", event);
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
            String reason = (response instanceof Response) ? ((Response)response).getReason() : null;
            int status = response.getStatus();

            // intentionally set response status to OK (this is a test to see what is actually logged)
            response.setStatus(200);
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("Error %d: %s%n", status, reason);
        }
    }

    public static Stream<Arguments> data()
    {
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[]{new HelloServlet(), "/test/", "GET /test/ HTTP/1.1 200"});
        data.add(new Object[]{new AsyncOnTimeoutCompleteServlet(), "/test/", "GET /test/ HTTP/1.1 200"});
        data.add(new Object[]{new AsyncOnTimeoutDispatchServlet(), "/test/", "GET /test/ HTTP/1.1 200"});
        data.add(new Object[]{new AsyncOnStartIOExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500"});
        data.add(new Object[]{new ResponseSendErrorServlet(), "/test/", "GET /test/ HTTP/1.1 500"});
        data.add(new Object[]{new ServletExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500"});
        data.add(new Object[]{new IOExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500"});
        data.add(new Object[]{new RuntimeExceptionServlet(), "/test/", "GET /test/ HTTP/1.1 500"});

        return data.stream().map(Arguments::of);
    }

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection.
     * This handler chain is setup to look like Jetty versions up to 9.2.
     * Default configuration.
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerCollection(Servlet testServlet, String requestPath, String expectedLogEntry) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[]{connector});

        // First the behavior as defined in etc/jetty.xml
        // id="Handlers"
        org.eclipse.jetty.server.Handler.Collection handlers = new org.eclipse.jetty.server.Handler.Collection();
        // id="Contexts"
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        // id="DefaultHandler"
        DefaultHandler defaultHandler = new DefaultHandler();

        handlers.setHandlers(contexts, defaultHandler);
        server.setHandler(handlers);

        // Next the behavior as defined by etc/jetty-requestlog.xml
        // the id="RequestLog"
        CaptureLog captureLog = new CaptureLog();
        server.setRequestLog(captureLog);

        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app.getCoreContextHandler());

        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder, "/test/*");

        try (StacklessLogging scope = new StacklessLogging(HttpChannel.class))
        {
            server.start();

            Assertions.assertTimeoutPreemptively(ofSeconds(4), () ->
            {
                connector.addBean(new HttpChannel.Listener()
                {
                    @Override
                    public void onComplete(Request request)
                    {
                        assertRequestLog(expectedLogEntry, captureLog);
                    }
                });
                
                String host = connector.getHost();
                if (host == null)
                    host = "localhost";
                
                int port = connector.getLocalPort();
                URI serverUri = new URI("http", null, host, port, requestPath, null, null);

                // Make call to test handler
                HttpURLConnection connection = (HttpURLConnection)serverUri.toURL().openConnection();
                try
                {
                    connection.setAllowUserInteraction(false);
                }
                finally
                {
                    connection.disconnect();
                }
            });
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection.
     * and also with the default ErrorHandler as server bean in place.
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerCollectionErrorHandlerServerBean(Servlet testServlet, String requestPath, String expectedLogEntry) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[]{connector});

        ErrorHandler errorHandler = new ErrorHandler();
        server.addBean(errorHandler);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        DefaultHandler defaultHandler = new DefaultHandler();
        Handler.Collection handlers = new Handler.Collection();
        handlers.setHandlers(contexts, defaultHandler);
        server.setHandler(handlers);

        // Next the behavior as defined by etc/jetty-requestlog.xml
        // the id="RequestLog"
        CaptureLog captureLog = new CaptureLog();
        server.setRequestLog(captureLog);

        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app.getCoreContextHandler());

        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder, "/test/*");

        try (StacklessLogging scope = new StacklessLogging(HttpChannel.class))
        {
            server.start();

            Assertions.assertTimeoutPreemptively(ofSeconds(4), () ->
            {
                connector.addBean(new HttpChannel.Listener()
                {
                    @Override
                    public void onComplete(Request request)
                    {
                        assertRequestLog(expectedLogEntry, captureLog);
                    }
                });
                
                String host = connector.getHost();
                if (host == null)
                    host = "localhost";

                int port = connector.getLocalPort();
                URI serverUri = new URI("http", null, host, port, requestPath, null, null);

                // Make call to test handler
                HttpURLConnection connection = (HttpURLConnection)serverUri.toURL().openConnection();
                try
                {
                    connection.setAllowUserInteraction(false);
                }
                finally
                {
                    connection.disconnect();
                }
            });
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * Test a RequestLogHandler at the end of a HandlerCollection
     * using servlet specific error page mapping.
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerCollectionSimpleErrorPageMapping(Servlet testServlet, String requestPath, String expectedLogEntry) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[]{connector});

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        Handler.Collection handlers = new Handler.Collection();
        handlers.setHandlers(contexts, new DefaultHandler());
        server.setHandler(handlers);

        // Next the behavior as defined by etc/jetty-requestlog.xml
        // the id="RequestLog"
        CaptureLog captureLog = new CaptureLog();
        server.setRequestLog(captureLog);

        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app.getCoreContextHandler());

        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder, "/test");
        app.addServlet(CustomErrorServlet.class, "/errorpage");

        // Add error page mapping
        ErrorPageErrorHandler errorMapper = new ErrorPageErrorHandler();
        errorMapper.addErrorPage(500, "/errorpage");
        app.setErrorHandler(errorMapper);

        try (StacklessLogging scope = new StacklessLogging(HttpChannel.class))
        {
            server.start();

            Assertions.assertTimeoutPreemptively(ofSeconds(4), () ->
            {
                connector.addBean(new HttpChannel.Listener()
                {
                    @Override
                    public void onComplete(Request request)
                    {
                        assertRequestLog(expectedLogEntry, captureLog);
                    }
                });
                
                String host = connector.getHost();
                if (host == null)
                    host = "localhost";

                int port = connector.getLocalPort();
                URI serverUri = new URI("http", null, host, port, requestPath, null, null);

                // Make call to test handler
                HttpURLConnection connection = (HttpURLConnection)serverUri.toURL().openConnection();
                try
                {
                    connection.setAllowUserInteraction(false);
                }
                finally
                {
                    connection.disconnect();
                }
            });
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * Test an alternate (proposed) setup for using RequestLogHandler in a wrapped style
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerWrapped(Servlet testServlet, String requestPath, String expectedLogEntry) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[]{connector});

        // First the behavior as defined in etc/jetty.xml (as is)
        // id="Contexts"
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        // id="DefaultHandler"
        DefaultHandler defaultHandler = new DefaultHandler();
        Handler.Collection handlers = new Handler.Collection();
        handlers.setHandlers(contexts, defaultHandler);
        server.setHandler(handlers);

        // Next the proposed behavioral change to etc/jetty-requestlog.xml
        // the id="RequestLog"
        CaptureLog captureLog = new CaptureLog();
        server.setRequestLog(captureLog);

        // Lastly, the behavior as defined by deployment of a webapp
        // Add the Servlet Context
        ServletContextHandler app = new ServletContextHandler(ServletContextHandler.SESSIONS);
        app.setContextPath("/");
        contexts.addHandler(app.getCoreContextHandler());

        // Add the test servlet
        ServletHolder testHolder = new ServletHolder(testServlet);
        app.addServlet(testHolder, "/test");
        app.addServlet(CustomErrorServlet.class, "/errorpage");

        // Add error page mapping
        ErrorPageErrorHandler errorMapper = new ErrorPageErrorHandler();
        errorMapper.addErrorPage(500, "/errorpage");
        app.setErrorHandler(errorMapper);

        try
        {
            server.start();

            Assertions.assertTimeoutPreemptively(ofSeconds(4), () ->
            {
                connector.addBean(new HttpChannel.Listener()
                {
                    @Override
                    public void onComplete(Request request)
                    {
                        assertRequestLog(expectedLogEntry, captureLog);
                    }
                });

                String host = connector.getHost();
                if (host == null)
                    host = "localhost";

                int port = connector.getLocalPort();
                URI serverUri = new URI("http", null, host, port, "/test", null, null);

                // Make call to test handler
                HttpURLConnection connection = (HttpURLConnection)serverUri.toURL().openConnection();
                try
                {
                    connection.setAllowUserInteraction(false);
                }
                finally
                {
                    connection.disconnect();
                }
            });
        }
        finally
        {
            server.stop();
        }
    }

    private void assertRequestLog(final String expectedLogEntry, CaptureLog captureLog)
    {
        assertThat("Request log size", captureLog.captured, not(empty()));
        assertThat("Request log entry", captureLog.captured.get(0), is(expectedLogEntry));
    }
}
