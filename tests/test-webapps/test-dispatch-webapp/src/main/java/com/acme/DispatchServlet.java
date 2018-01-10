//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package com.acme;

import static javax.servlet.RequestDispatcher.FORWARD_PATH_INFO;
import static javax.servlet.RequestDispatcher.FORWARD_QUERY_STRING;
import static javax.servlet.RequestDispatcher.FORWARD_SERVLET_PATH;
import static javax.servlet.RequestDispatcher.INCLUDE_PATH_INFO;
import static javax.servlet.RequestDispatcher.INCLUDE_QUERY_STRING;
import static javax.servlet.RequestDispatcher.INCLUDE_REQUEST_URI;
import static javax.servlet.RequestDispatcher.INCLUDE_SERVLET_PATH;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class DispatchServlet extends HttpServlet
{

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        doGet(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        Integer depth = (Integer)request.getAttribute("depth");
        if (depth==null)
            depth=1;
        else
            depth=depth+1;
        request.setAttribute("depth", depth);
        
        String path=request.getServletPath();
        String info=request.getPathInfo();
        String query=request.getQueryString();
        
        boolean include=request.getAttribute(INCLUDE_REQUEST_URI)!=null;
        
        String tpath = include?(String)request.getAttribute(INCLUDE_SERVLET_PATH):path;
        String tinfo = include?(String)request.getAttribute(INCLUDE_PATH_INFO):info;
                
        if ("/forward".equals(tpath))
        {
            getServletContext().getRequestDispatcher(tinfo+"?depth="+depth+"&p"+depth+"="+"ABCDEFGHI".charAt(depth)).forward(request, response);
        }
        else if ("/include".equals(tpath))
        {
            response.setContentType("text/html");
            PrintWriter out = response.getWriter();
            out.println("<h1>Dispatch Depth="+depth+"</h1><pre>");
            out.printf("            %30s%30s%30s%n","REQUEST","FORWARD","INCLUDE");
            out.printf("servletPath:%30s%30s%30s%n",path,request.getAttribute(FORWARD_SERVLET_PATH),request.getAttribute(INCLUDE_SERVLET_PATH));
            out.printf("   pathInfo:%30s%30s%30s%n",info,request.getAttribute(FORWARD_PATH_INFO),request.getAttribute(INCLUDE_PATH_INFO));
            out.printf("      query:%30s%30s%30s%n",query,request.getAttribute(FORWARD_QUERY_STRING),request.getAttribute(INCLUDE_QUERY_STRING));
            out.println();
            printParameters(out, request.getParameterMap());
            out.println("</pre>");
            out.println("<hr/>");
            getServletContext().getRequestDispatcher(tinfo+"?depth="+depth+"&p"+depth+"="+"BCDEFGHI".charAt(depth)).include(request, response);
            out.println("<hr/>");
            out.println("<h1>Dispatch Depth="+depth+"</h1><pre>");
            out.printf("            %30s%30s%30s%n","REQUEST","FORWARD","INCLUDE");
            out.printf("servletPath:%30s%30s%30s%n",path,request.getAttribute(FORWARD_SERVLET_PATH),request.getAttribute(INCLUDE_SERVLET_PATH));
            out.printf("   pathInfo:%30s%30s%30s%n",info,request.getAttribute(FORWARD_PATH_INFO),request.getAttribute(INCLUDE_PATH_INFO));
            out.printf("      query:%30s%30s%30s%n",query,request.getAttribute(FORWARD_QUERY_STRING),request.getAttribute(INCLUDE_QUERY_STRING));
            out.println();
            printParameters(out, request.getParameterMap());
            out.println("</pre>");
        }
        else
        {
            response.setContentType("text/html");
            PrintWriter out = response.getWriter();
            out.println("<h1>Dispatch Depth="+depth+"</h1><pre>");
            out.printf("            %30s%30s%30s%n","REQUEST","FORWARD","INCLUDE");
            out.printf("servletPath:%30s%30s%30s%n",path,request.getAttribute(FORWARD_SERVLET_PATH),request.getAttribute(INCLUDE_SERVLET_PATH));
            out.printf("   pathInfo:%30s%30s%30s%n",info,request.getAttribute(FORWARD_PATH_INFO),request.getAttribute(INCLUDE_PATH_INFO));
            out.printf("      query:%30s%30s%30s%n",query,request.getAttribute(FORWARD_QUERY_STRING),request.getAttribute(INCLUDE_QUERY_STRING));
            out.println();
            printParameters(out, request.getParameterMap());
            out.println("</pre>");
        }
    }
    
    private static void printParameters(PrintWriter out, Map<String,String[]> params)
    {
        List<String> names = new ArrayList<>(params.keySet());
        Collections.sort(names);
        
        for (String name: names)
            out.printf("%10s : %s%n", name,Arrays.asList(params.get(name)));
    }

}
