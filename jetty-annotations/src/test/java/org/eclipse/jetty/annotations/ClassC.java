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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.annotation.FilterMapping;
import javax.servlet.http.annotation.InitParam;
import javax.servlet.http.annotation.Servlet;
import javax.servlet.http.annotation.ServletFilter;
import javax.servlet.http.annotation.jaxrs.GET;
import javax.servlet.http.annotation.jaxrs.POST;


@Servlet(urlMappings = { "/foo/*", "/bah/*" }, name="CServlet", initParams={@InitParam(name="x", value="y")})
@ServletFilter(filterName="CFilter", filterMapping=@FilterMapping(dispatcherTypes={DispatcherType.REQUEST}, urlPattern = {"/*"}), initParams={@InitParam(name="a", value="99")})
@RunAs("admin")
public class ClassC
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
    
    @GET()
    @POST()
    public void anything (HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
    {
        response.setContentType("text/html");
        response.getWriter().println("<h1>Pojo Servlet</h1>");
        response.getWriter().println("Acting like a Servlet.");
    }
    
    
    public void doFilter (HttpServletRequest request, HttpServletResponse response, FilterChain chain)
    throws java.io.IOException, javax.servlet.ServletException
    {
        HttpSession session = request.getSession(true);
        String val = request.getParameter("action");
        if (val!=null)
            session.setAttribute("action", val);
        chain.doFilter(request, response);
    }
}
