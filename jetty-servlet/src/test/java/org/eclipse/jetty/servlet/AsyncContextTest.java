package org.eclipse.jetty.servlet;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.AsyncContext;
import org.eclipse.jetty.server.AsyncContinuation;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * This tests the correct functioning of the AsyncContext
 * 
 * tests for #371649 and #371635
 */
public class AsyncContextTest
{

    private Server _server = new Server();
    private ServletContextHandler _contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    private LocalConnector _connector = new LocalConnector();

    @Before
    public void setUp() throws Exception
    {
        _connector.setMaxIdleTime(30000);
        _server.setConnectors(new Connector[]
        { _connector });

        _contextHandler.setContextPath("/");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()),"/servletPath");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()),"/path with spaces/servletPath");
        _contextHandler.addServlet(new ServletHolder(new TestServlet2()),"/servletPath2");
        _contextHandler.addServlet(new ServletHolder(new ForwardingServlet()),"/forward");
        _contextHandler.addServlet(new ServletHolder(new AsyncDispatchingServlet()),"/dispatchingServlet");

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]
        { _contextHandler, new DefaultHandler() });

        _server.setHandler(handlers);
        _server.start();
    }

    @Test
    public void testSimpleAsyncContext() throws Exception
    {
        String request = "GET /servletPath HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Connection: close\r\n" + "\r\n";

        String responseString = _connector.getResponses(request);

        BufferedReader br = parseHeader(responseString);

        Assert.assertEquals("servlet gets right path","doGet:getServletPath:/servletPath",br.readLine());
        Assert.assertEquals("async context gets right path in get","doGet:async:getServletPath:/servletPath",br.readLine());
        Assert.assertEquals("async context gets right path in async","async:run:attr:servletPath:/servletPath",br.readLine());
    }

    @Test
    public void testDispatchAsyncContext() throws Exception
    {
        String request = "GET /servletPath?dispatch=true HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Connection: close\r\n" + "\r\n";
        String responseString = _connector.getResponses(request);

        BufferedReader br = parseHeader(responseString);

        Assert.assertEquals("servlet gets right path","doGet:getServletPath:/servletPath2",br.readLine());
        Assert.assertEquals("async context gets right path in get","doGet:async:getServletPath:/servletPath2",br.readLine());
        Assert.assertEquals("servlet path attr is original","async:run:attr:servletPath:/servletPath",br.readLine());
        Assert.assertEquals("path info attr is correct","async:run:attr:pathInfo:null",br.readLine());
        Assert.assertEquals("query string attr is correct","async:run:attr:queryString:dispatch=true",br.readLine());
        Assert.assertEquals("context path attr is correct","async:run:attr:contextPath:",br.readLine());
        Assert.assertEquals("request uri attr is correct","async:run:attr:requestURI:/servletPath",br.readLine());
    }
    
    @Test
    public void testDispatchAsyncContextEncodedPathAndQueryString() throws Exception
    {
        String request = "GET /path%20with%20spaces/servletPath?dispatch=true&queryStringWithEncoding=space%20space HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Connection: close\r\n" + "\r\n";
        String responseString = _connector.getResponses(request);
        
        BufferedReader br = parseHeader(responseString);
        
        assertThat("servlet gets right path",br.readLine(),equalTo("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get",br.readLine(), equalTo("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original",br.readLine(),equalTo("async:run:attr:servletPath:/path with spaces/servletPath"));
        assertThat("path info attr is correct",br.readLine(),equalTo("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct",br.readLine(),equalTo("async:run:attr:queryString:dispatch=true&queryStringWithEncoding=space%20space"));
        assertThat("context path attr is correct",br.readLine(),equalTo("async:run:attr:contextPath:"));
        assertThat("request uri attr is correct",br.readLine(),equalTo("async:run:attr:requestURI:/path%20with%20spaces/servletPath"));
    }

    @Test
    public void testSimpleWithContextAsyncContext() throws Exception
    {
        _contextHandler.setContextPath("/foo");

        String request = "GET /foo/servletPath HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Connection: close\r\n" + "\r\n";

        String responseString = _connector.getResponses(request);

        BufferedReader br = parseHeader(responseString);

        Assert.assertEquals("servlet gets right path","doGet:getServletPath:/servletPath",br.readLine());
        Assert.assertEquals("async context gets right path in get","doGet:async:getServletPath:/servletPath",br.readLine());
        Assert.assertEquals("async context gets right path in async","async:run:attr:servletPath:/servletPath",br.readLine());
    }

    @Test
    public void testDispatchWithContextAsyncContext() throws Exception
    {
        _contextHandler.setContextPath("/foo");

        String request = "GET /foo/servletPath?dispatch=true HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Connection: close\r\n" + "\r\n";

        String responseString = _connector.getResponses(request);

        BufferedReader br = parseHeader(responseString);

        Assert.assertEquals("servlet gets right path","doGet:getServletPath:/servletPath2",br.readLine());
        Assert.assertEquals("async context gets right path in get","doGet:async:getServletPath:/servletPath2",br.readLine());
        Assert.assertEquals("servlet path attr is original","async:run:attr:servletPath:/servletPath",br.readLine());
        Assert.assertEquals("path info attr is correct","async:run:attr:pathInfo:null",br.readLine());
        Assert.assertEquals("query string attr is correct","async:run:attr:queryString:dispatch=true",br.readLine());
        Assert.assertEquals("context path attr is correct","async:run:attr:contextPath:/foo",br.readLine());
        Assert.assertEquals("request uri attr is correct","async:run:attr:requestURI:/foo/servletPath",br.readLine());
    }

    @Test
    public void testDispatch() throws Exception
    {
        String request = "GET /forward HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n" + "Connection: close\r\n"
                + "\r\n";

        String responseString = _connector.getResponses(request);

        BufferedReader br = parseHeader(responseString);

        assertThat("!ForwardingServlet",br.readLine(),equalTo("Dispatched back to ForwardingServlet"));
    }

    @Test
    public void testDispatchRequestResponse() throws Exception
    {
        String request = "GET /forward?dispatchRequestResponse=true HTTP/1.1\r\n" + "Host: localhost\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n" + "Connection: close\r\n" + "\r\n";

        String responseString = _connector.getResponses(request);

        BufferedReader br = parseHeader(responseString);

        assertThat("!AsyncDispatchingServlet",br.readLine(),equalTo("Dispatched back to AsyncDispatchingServlet"));
    }

    private BufferedReader parseHeader(String responseString) throws IOException
    {
        BufferedReader br = new BufferedReader(new StringReader(responseString));

        assertEquals("HTTP/1.1 200 OK",br.readLine());

        br.readLine();// connection close
        br.readLine();// server
        br.readLine();// empty
        return br;
    }

    private class ForwardingServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            if (((Request)request).getDispatcherType() == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to ForwardingServlet");
            }
            else
            {
                request.getRequestDispatcher("/dispatchingServlet").forward(request,response);
            }
        }
    }

    private class AsyncDispatchingServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, final HttpServletResponse response) throws ServletException, IOException
        {
            Request request = (Request)req;
            if (request.getDispatcherType() == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to AsyncDispatchingServlet");
            }
            else
            {
                final AsyncContext asyncContext;
                if (request.getParameter("dispatchRequestResponse") != null)
                {
                    asyncContext = request.startAsync(request,response);
                }
                else
                {
                    asyncContext = request.startAsync();
                }

                new Thread(new DispatchingRunnable(asyncContext)).start();
            }
        }
    }

    private class DispatchingRunnable implements Runnable
    {
        private AsyncContext asyncContext;

        public DispatchingRunnable(AsyncContext asyncContext)
        {
            this.asyncContext = asyncContext;
        }

        public void run()
        {
            asyncContext.dispatch();
        }
    }

    @After
    public void tearDown() throws Exception
    {
        _server.stop();
        _server.join();
    }

    private class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            AsyncContinuation continuation = (AsyncContinuation)ContinuationSupport.getContinuation(request);

            if (request.getParameter("dispatch") != null)
            {
                continuation.suspend();
                continuation.dispatch("/servletPath2");
                // AsyncContext asyncContext = request.startAsync(request,response);
            }
            else
            {
                response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");

                continuation.suspend();

                // AsyncContext asyncContext = request.startAsync(request,response);

                response.getOutputStream().print("doGet:async:getServletPath:" + ((HttpServletRequest)continuation.getRequest()).getServletPath() + "\n");

                Runnable runable = new AsyncRunnable(continuation);
                new Thread(runable).start();
                // asyncContext.start(new AsyncRunnable(asyncContext));
            }
            return;

        }
    }

    private class TestServlet2 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            AsyncContinuation continuation = (AsyncContinuation)ContinuationSupport.getContinuation(request);

            response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");

            continuation.suspend();
            // AsyncContext asyncContext = request.startAsync(request, response);

            response.getOutputStream().print("doGet:async:getServletPath:" + ((HttpServletRequest)continuation.getRequest()).getServletPath() + "\n");
            Runnable runable = new AsyncRunnable(continuation);
            new Thread(runable).start();
            // asyncContext.start(new AsyncRunnable(asyncContext));

            return;
        }
    }

    private class AsyncRunnable implements Runnable
    {
        private AsyncContinuation _continuation;

        public AsyncRunnable(AsyncContinuation continuation)
        {
            _continuation = continuation;
        }

        public void run()
        {
            HttpServletRequest req = (HttpServletRequest)_continuation.getRequest();

            try
            {
                _continuation.getResponse().getOutputStream().print("async:run:attr:servletPath:" + req.getAttribute(AsyncContext.ASYNC_SERVLET_PATH) + "\n");
                _continuation.getResponse().getOutputStream().print("async:run:attr:pathInfo:" + req.getAttribute(AsyncContext.ASYNC_PATH_INFO) + "\n");
                _continuation.getResponse().getOutputStream().print("async:run:attr:queryString:" + req.getAttribute(AsyncContext.ASYNC_QUERY_STRING) + "\n");
                _continuation.getResponse().getOutputStream().print("async:run:attr:contextPath:" + req.getAttribute(AsyncContext.ASYNC_CONTEXT_PATH) + "\n");
                _continuation.getResponse().getOutputStream().print("async:run:attr:requestURI:" + req.getAttribute(AsyncContext.ASYNC_REQUEST_URI) + "\n");
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }

            _continuation.complete();
        }
    }

}
