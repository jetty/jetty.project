// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.servlet;


import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

public class DispatcherTest extends TestCase
{
    private Server _server = new Server();
    private LocalConnector _connector;
    private ServletContextHandler _context;

    protected void setUp() throws Exception
    {
        _server = new Server();
        _server.setSendServerVersion(false);
        _connector = new LocalConnector();
        _context = new ServletContextHandler();
        _context.setContextPath("/context");
        _server.setHandler(_context);
        _server.addConnector( _connector );

        _server.start();
    }

    public void testForward() throws Exception
    {
        _context.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _context.addServlet(AssertForwardServlet.class, "/AssertForwardServlet/*");

        String expected=
            "HTTP/1.1 200 OK\r\n"+
            "Content-Type: text/html\r\n"+
            "Content-Length: 0\r\n"+
            "\r\n";

        String responses = _connector.getResponses("GET /context/ForwardServlet?do=assertforward&do=more&test=1 HTTP/1.1\n" + "Host: localhost\n\n");
        
        assertEquals(expected, responses);        
    }
    
    public void testInclude() throws Exception
    {
        _context.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _context.addServlet(AssertIncludeServlet.class, "/AssertIncludeServlet/*");

        String expected=
            "HTTP/1.1 200 OK\r\n"+
            "Content-Length: 0\r\n"+
            "\r\n";

        String responses = _connector.getResponses("GET /context/IncludeServlet?do=assertinclude&do=more&test=1 HTTP/1.1\n" + "Host: localhost\n\n");
        
        assertEquals(expected, responses);         
    }
    
    public void testForwardThenInclude() throws Exception
    {
        _context.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _context.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _context.addServlet(AssertForwardIncludeServlet.class, "/AssertForwardIncludeServlet/*");

        String expected=
            "HTTP/1.1 200 OK\r\n"+
            "Content-Length: 0\r\n"+
            "\r\n";

        String responses = _connector.getResponses("GET /context/ForwardServlet/forwardpath?do=include HTTP/1.1\n" + "Host: localhost\n\n");
        
        assertEquals(expected, responses);
    }
    
    public void testIncludeThenForward() throws Exception
    {
        _context.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _context.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _context.addServlet(AssertIncludeForwardServlet.class, "/AssertIncludeForwardServlet/*");
        

        String expected=
            "HTTP/1.1 200 OK\r\n"+
            "Transfer-Encoding: chunked\r\n"+
            "\r\n"+
            "0\r\n"+
            "\r\n";

        String responses = _connector.getResponses("GET /context/IncludeServlet/includepath?do=forward HTTP/1.1\n" + "Host: localhost\n\n");
        
        assertEquals(expected, responses);
    }
    
    public static class ForwardServlet extends HttpServlet implements Servlet 
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
        {
            RequestDispatcher dispatcher = null;
        
            if(request.getParameter("do").equals("include"))
                dispatcher = getServletContext().getRequestDispatcher("/IncludeServlet/includepath?do=assertforwardinclude");
            else if(request.getParameter("do").equals("assertincludeforward"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertIncludeForwardServlet/assertpath?do=end");
            else if(request.getParameter("do").equals("assertforward"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertForwardServlet?do=end&do=the");
            dispatcher.forward(request, response);
        }       
    }
    
    public static class IncludeServlet extends HttpServlet implements Servlet 
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
        {
            RequestDispatcher dispatcher = null;
        
            if(request.getParameter("do").equals("forward"))
                dispatcher = getServletContext().getRequestDispatcher("/ForwardServlet/forwardpath?do=assertincludeforward");
            else if(request.getParameter("do").equals("assertforwardinclude"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertForwardIncludeServlet/assertpath?do=end");
            else if(request.getParameter("do").equals("assertinclude"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertIncludeServlet?do=end&do=the");
            dispatcher.include(request, response);
        }       
    }

    public static class AssertForwardServlet extends HttpServlet implements Servlet
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            assertEquals( "/context/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals( "/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH) );
            assertEquals( "/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals( null, request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals( "do=assertforward&do=more&test=1", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING) );

            
            List expectedAttributeNames = Arrays.asList(new String[] {
                Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, 
                Dispatcher.FORWARD_SERVLET_PATH, Dispatcher.FORWARD_QUERY_STRING
            });
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));
            
            
            assertEquals(null, request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=end&do=the&test=1", request.getQueryString());
            assertEquals("/context/AssertForwardServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/AssertForwardServlet", request.getServletPath());
            
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);

        }
    }

    public static class AssertIncludeServlet extends HttpServlet implements Servlet 
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            assertEquals( "/context/AssertIncludeServlet", request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertEquals( "/context", request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH) );
            assertEquals( "/AssertIncludeServlet", request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertEquals( null, request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertEquals( "do=end&do=the", request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));    
            
            List expectedAttributeNames = Arrays.asList(new String[] {
                Dispatcher.INCLUDE_REQUEST_URI, Dispatcher.INCLUDE_CONTEXT_PATH, 
                Dispatcher.INCLUDE_SERVLET_PATH, Dispatcher.INCLUDE_QUERY_STRING
            });
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            
            
            assertEquals(null, request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=assertinclude&do=more&test=1", request.getQueryString());
            assertEquals("/context/IncludeServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/IncludeServlet", request.getServletPath());
            
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            
        }
    }
    
    
    public static class AssertForwardIncludeServlet extends HttpServlet implements Servlet 
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
        {
            // include doesn't hide forward
            assertEquals( "/context/ForwardServlet/forwardpath", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals( "/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH) );
            assertEquals( "/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals( "/forwardpath", request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals( "do=include", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING) );
            
            assertEquals( "/context/AssertForwardIncludeServlet/assertpath", request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertEquals( "/context", request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH) );
            assertEquals( "/AssertForwardIncludeServlet", request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertEquals( "/assertpath", request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertEquals( "do=end", request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));    
            
            
            List expectedAttributeNames = Arrays.asList(new String[] {
                Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, Dispatcher.FORWARD_SERVLET_PATH, 
                Dispatcher.FORWARD_PATH_INFO, Dispatcher.FORWARD_QUERY_STRING,
                Dispatcher.INCLUDE_REQUEST_URI, Dispatcher.INCLUDE_CONTEXT_PATH, Dispatcher.INCLUDE_SERVLET_PATH, 
                Dispatcher.INCLUDE_PATH_INFO, Dispatcher.INCLUDE_QUERY_STRING
            });
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));            
            
            
            assertEquals("/includepath", request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=assertforwardinclude", request.getQueryString());
            assertEquals("/context/IncludeServlet/includepath", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/IncludeServlet", request.getServletPath());
            
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
        }       
     }
    
    public static class AssertIncludeForwardServlet extends HttpServlet implements Servlet
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
        {
            // forward hides include
            assertEquals( null, request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertEquals( null, request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH) );
            assertEquals( null, request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertEquals( null, request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertEquals( null, request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));    

            assertEquals( "/context/IncludeServlet/includepath", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals( "/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH) );
            assertEquals( "/IncludeServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals( "/includepath", request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals( "do=forward", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING) );
            
            
            List expectedAttributeNames = Arrays.asList(new String[] {
                Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, Dispatcher.FORWARD_SERVLET_PATH, 
                Dispatcher.FORWARD_PATH_INFO, Dispatcher.FORWARD_QUERY_STRING,
            });
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));
            
            
            assertEquals("/assertpath", request.getPathInfo());
            assertEquals(null, request.getPathTranslated()); 
            assertEquals("do=end", request.getQueryString());
            assertEquals("/context/AssertIncludeForwardServlet/assertpath", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/AssertIncludeForwardServlet", request.getServletPath());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
        }               
    }
    
}
