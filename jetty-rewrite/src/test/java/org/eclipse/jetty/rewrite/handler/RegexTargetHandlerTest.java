// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rewrite.handler;

import static junit.framework.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import junit.framework.Assert;

import org.eclipse.jetty.rewrite.handler.RegexTargetHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RegexTargetHandlerTest
{
    private static Server __server = new Server(0);
    private static int __port;
    
    @BeforeClass
    public static void setup() throws Exception
    {

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        __server.setHandler(context);

        // Serve some hello world servlets
        context.addServlet(DispatchServletServlet.class,"/dispatch/*");
        context.addServlet(new ServletHolder("HelloAll",new HelloServlet("Hello World")),"/*");
        context.addServlet(new ServletHolder("Italian",new HelloServlet("Buongiorno Mondo")),"/it/*");
        context.addServlet(new ServletHolder("French", new HelloServlet("Bonjour le Monde")),"/fr/*");

        RegexTargetHandler regexHandler=new RegexTargetHandler();
        regexHandler.setHandler(context.getHandler());
        context.setHandler(regexHandler);
        
        context.getInitParams().put(RegexTargetHandler.REGEX_MAPPINGS,
                "  .*\\.fr==French,   \n"+
                "/ciao(/.*)$==Italian");
        
        __server.start();
        
        __port=__server.getConnectors()[0].getLocalPort();
    }

    @AfterClass
    public static void shutdown() throws Exception
    {
        __server.stop();
    }
    
    
    @Test
    public void testNormal() throws Exception
    {
        String[] response=getResponse("/normal");
        assertEquals("HTTP/1.1 200 OK",response[0]);
        assertEquals("Hello World",response[1]);
        assertEquals("",response[2]);
        assertEquals("/normal",response[3]);
        
        response=getResponse("/it/info");
        assertEquals("HTTP/1.1 200 OK",response[0]);
        assertEquals("Buongiorno Mondo",response[1]);
        assertEquals("/it",response[2]);
        assertEquals("/info",response[3]);
    }
    
    @Test
    public void testFullMatch() throws Exception
    {
        String[] response=getResponse("/some/thing.fr");
        assertEquals("HTTP/1.1 200 OK",response[0]);
        assertEquals("Bonjour le Monde",response[1]);
        assertEquals("/some/thing.fr",response[2]);
        assertEquals("null",response[3]);
    }
    
    @Test
    public void testCaptureMatch() throws Exception
    {
        String[] response=getResponse("/ciao/info");
        assertEquals("HTTP/1.1 200 OK",response[0]);
        assertEquals("Buongiorno Mondo",response[1]);
        assertEquals("/ciao",response[2]);
        assertEquals("/info",response[3]);
    }
    
    @Test
    public void testDispatchFullMatch() throws Exception
    {
        String[] response=getResponse("/dispatch/xxx?forward=/some/thing.fr");
        assertEquals("HTTP/1.1 200 OK",response[0]);
        assertEquals("Bonjour le Monde",response[1]);
        assertEquals("/some/thing.fr",response[2]);
        assertEquals("null",response[3]);
    }
    
    @Test
    public void testDispatchCaptureMatch() throws Exception
    {
        String[] response=getResponse("/dispatch/xxx?forward=/ciao/info");
        assertEquals("HTTP/1.1 200 OK",response[0]);
        assertEquals("Buongiorno Mondo",response[1]);
        assertEquals("/ciao",response[2]);
        assertEquals("/info",response[3]);
    }
    
    
    private String[] getResponse(String uri) throws Exception
    {
        Socket socket = new Socket("127.0.0.1",__port);
        
        PrintWriter out = new PrintWriter(socket.getOutputStream());
        out.print("GET "+uri+" HTTP/1.0\r\n\r\n");
        out.flush();
        
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        
        String[] response=new String[4];
        response[0]=in.readLine();
        //System.err.println(response[0]);
        
        String line=in.readLine();
        while(line.length()>0)
            line=in.readLine();
        
        response[1]=in.readLine();
        //System.err.println(response[1]);
        response[2]=in.readLine();
        //System.err.println(response[2]);
        response[3]=in.readLine();
        //System.err.println(response[3]);
   
        socket.close();
        return response;
    }
    
    
    public static class HelloServlet extends HttpServlet implements Servlet
    {
        final String _hello;
        
        public HelloServlet(String hello)
        {
            _hello=hello;
        }
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setStatus(200);
            response.getWriter().println(_hello);
            response.getWriter().println(request.getServletPath());
            response.getWriter().println(request.getPathInfo());
        }
    }
    
    
    public static class DispatchServletServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;

            if(request.getParameter("include")!=null)
            {
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("include"));
                dispatcher.include(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
            }
            else if(request.getParameter("forward")!=null)
            {
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("forward"));
                dispatcher.forward(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
            }
            
        }
    }
}
