package org.eclipse.jetty.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.AsyncContinuation;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.log.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * This tests the correct functioning of the AsyncContext
 * 
 * @author tbecker
 * 
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
     
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]
        { _contextHandler, new DefaultHandler() });

        _server.setHandler(handlers);
        _server.start();
    }

    @Test
    //Ignore ("failing test illustrating potential issue")
    public void testSimpleAsyncContext() throws Exception
    {
        String request = "GET /servletPath HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Connection: close\r\n" + "\r\n";
        String responseString = _connector.getResponses(request);
        
        System.out.println(responseString);
        
        Assert.assertTrue("check in servlet doGet", responseString.contains("doGet:getServletPath:/servletPath"));
        Assert.assertTrue("check in servlet doGet via async", responseString.contains("doGet:async:getServletPath:/servletPath"));
        Assert.assertTrue("check in async runnable", responseString.contains("async:run:/servletPath"));
        Assert.assertTrue("async attr check: servlet path", responseString.contains("async:run:attr:servletPath:/servletPath"));
        // should validate these are indeed correct
        Assert.assertTrue("async attr check: path info", responseString.contains("async:run:attr:pathInfo:null"));
        Assert.assertTrue("async attr check: request uri", responseString.contains("async:run:attr:requestURI:/servletPath"));
        Assert.assertTrue("async attr check: query string", responseString.contains("async:run:attr:queryString:null"));
        Assert.assertTrue("async attr check: context path", responseString.contains("async:run:attr:contextPath:"));

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

        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");

            AsyncContext asyncContext = request.startAsync(request, response);
            
            response.getOutputStream().print("doGet:async:getServletPath:" + ((HttpServletRequest)asyncContext.getRequest()).getServletPath() + "\n");

            //Runnable runable = new AsyncRunnable(asyncContext);
            //new Thread(runable).start();
            asyncContext.start(new AsyncRunnable(asyncContext));
            
            return;
        }
    }

    private class AsyncRunnable implements Runnable
    {
        private AsyncContext _context;

        public AsyncRunnable(AsyncContext context)
        {
            _context = context;
        }

        @Override
        public void run()
        {
            HttpServletRequest req = (HttpServletRequest)_context.getRequest();
            
            assert (req.getServletPath().equals("/servletPath"));
            
            System.out.println(req.getServletPath());
            
            try
            {
                _context.getResponse().getOutputStream().print("async:run:" + req.getServletPath() + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:servletPath:" + req.getAttribute(AsyncContext.ASYNC_SERVLET_PATH) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:pathInfo:" + req.getAttribute(AsyncContext.ASYNC_PATH_INFO) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:requestURI:" + req.getAttribute(AsyncContext.ASYNC_REQUEST_URI) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:queryString:" + req.getAttribute(AsyncContext.ASYNC_QUERY_STRING) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:contextPath:" + req.getAttribute(AsyncContext.ASYNC_CONTEXT_PATH) + "\n");

            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            
            _context.complete();         
        }
    }

}
