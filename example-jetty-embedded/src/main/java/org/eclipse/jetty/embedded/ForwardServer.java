package org.eclipse.jetty.embedded;

//========================================================================
//Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.GenericServlet;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.rewrite.handler.RedirectPatternRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.RewritePatternRule;
import org.eclipse.jetty.rewrite.handler.Rule;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

public class ForwardServer
{
    
    private static Server _server;
    private static SelectChannelConnector _connector;
    private static HandlerCollection _handlerCollection;
    private static ServletContextHandler _contextHandler;
    private static RewriteHandler _rewriteHandler;

    /*
     *   http://localhost:8080/random?echo=echoText  -> echoText
     *   http://localhost:8080/?echo=echoText  -> txeTohce
     *   http://localhost:8080/context?echo=echoText -> echoText
     *   http://localhost:8080/context -> Roger That!
     *   http://localhost:8080/random -> Roger That!
     */
    
    public static void main( String[] args ) throws Exception
    {
        _server = new Server();
        _server.setSendServerVersion(false);
        _connector = new SelectChannelConnector();
        _connector.setPort(8080);
        
       
        _rewriteHandler = new RewriteHandler();
        _rewriteHandler.setRewriteRequestURI(true);
        _rewriteHandler.setRewritePathInfo(false);
        _rewriteHandler.setOriginalPathAttribute("requestedPath");
        
        RewritePatternRule rule0 = new RewritePatternRule();
        
        rule0.setPattern("/sample/*");
        rule0.setReplacement("/context");
        rule0.setTerminating(true);
        
        RewritePatternRule rule1 = new RewritePatternRule();
        
        rule1.setPattern("/goodies/*");
        rule1.setReplacement("/context");
        rule1.setTerminating(true);
        
        
        RewritePatternRule rule2 = new RewritePatternRule();
        
        rule2.setPattern("/*");
        rule2.setReplacement("/context");
        
        _rewriteHandler.setRules(new Rule[]{rule0, rule1, rule2});
              
        _handlerCollection = new HandlerCollection();
        
        _contextHandler = new ServletContextHandler();
        
        _contextHandler.setContextPath("/context");
        _handlerCollection.addHandler(_contextHandler);
        
        
        _contextHandler.addServlet(RogerThatServlet.class, "/*");
        _contextHandler.addServlet(ReserveEchoServlet.class,"/recho/*");
        _contextHandler.addServlet(EchoServlet.class, "/echo/*");
        
        _contextHandler.addFilter(ForwardFilter.class, "/*", FilterMapping.REQUEST);
    
        _rewriteHandler.setHandler(_handlerCollection);

        _server.setHandler(_rewriteHandler);
        _server.addConnector( _connector );

        _server.start();
        
        
       
    }
    
    public static class RogerThatServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            res.getWriter().print("Roger That!");
        }
    }

    public static class EchoServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            String echoText = req.getParameter("echo");
            
            if ( echoText == null )
            {
                throw new ServletException("echo is a required parameter");
            }
            else
            {
                res.getWriter().print(echoText);
            }
        }
    }
    
    public static class ReserveEchoServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            String echoText = req.getParameter("echo");
            
            if ( echoText == null )
            {
                throw new ServletException("echo is a required parameter");
            }
            else
            {
                res.getWriter().print(new StringBuffer(echoText).reverse().toString());
            }
        }
    }
    
    
    /*
     * Forward filter works with roger, echo and reverse echo servlets to test various 
     * forwarding bits using filters.
     * 
     * when there is an echo parameter and the path info is / it forwards to the reverse echo
     * anything else in the pathInfo and it sends straight to the echo servlet...otherwise its
     * all roger servlet
     */
    public static class ForwardFilter implements Filter
    {
        ServletContext servletContext;
        
        public void init(FilterConfig filterConfig) throws ServletException
        {
            servletContext = filterConfig.getServletContext().getContext("/context");
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            
            if ( servletContext == null || !(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse))
            {
                chain.doFilter(request,response);
                return;
            }
            
            HttpServletRequest req = (HttpServletRequest)request;
            HttpServletResponse resp = (HttpServletResponse)response;
             
            System.out.println("getPathInfo() -" + req.getPathInfo());
            System.out.println("getServletInfo() -" + req.getServletPath());
            
            
            if ( req.getParameter("echo") != null && "/".equals(req.getPathInfo()))
            {
                RequestDispatcher dispatcher = servletContext.getRequestDispatcher("/recho");
                dispatcher.forward(request,response);
            }
            else if ( req.getParameter("echo") != null )
            {
                RequestDispatcher dispatcher = servletContext.getRequestDispatcher("/echo");
                dispatcher.forward(request,response);
            }
            else
            {
                chain.doFilter(request,response);
                return;
            }
        }

        public void destroy()
        {
            
        }       
    }
    
}
