//  ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.annotations;

import java.io.IOException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.annotation.security.RunAs;
import javax.servlet.DispatcherType;
import javax.servlet.FilterChain;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;




@WebServlet(urlPatterns = { "/foo/*", "/bah/*" }, name="CServlet", initParams={@WebInitParam(name="x", value="y")}, loadOnStartup=2, asyncSupported=false)
@RunAs("admin")
public class ServletC extends HttpServlet
{
    @Resource (mappedName="foo")
    private Double foo;
    
    @PreDestroy
    public void pre ()
    {
        
    }
    
    @PostConstruct
    public void post()
    {
        
    }
 
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        response.setContentType("text/html");
        response.getWriter().println("<h1>Annotated Servlet</h1>");
        response.getWriter().println("Acting like a Servlet.");
    }
}
