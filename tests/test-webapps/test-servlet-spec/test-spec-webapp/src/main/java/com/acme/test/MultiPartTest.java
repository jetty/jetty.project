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

package com.acme.test;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.util.IO;
/**
 * MultiPartTest
 * 
 * Test Servlet 3.0 MultiPart Mime handling.
 * 
 *
 */

@MultipartConfig(location="foo/bar", maxFileSize=10240, maxRequestSize=-1, fileSizeThreshold=2048)
public class MultiPartTest extends HttpServlet 
{
    private ServletConfig config;
    
    
    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        this.config = config;
    }

    
    
    /* ------------------------------------------------------------ */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {

        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<HEAD><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></HEAD>");
            out.println("<body>");
            out.println("<h1>Results</h1>");
            out.println("<p>");

            Collection<Part> parts = request.getParts();
            out.println("<b>Parts:</b>&nbsp;"+parts.size());
            for (Part p: parts)
            {
                out.println("<h3>"+p.getName()+"</h3>");
                out.println("<b>Size:</b>&nbsp;"+p.getSize());
                if (p.getContentType() == null || p.getContentType().startsWith("text/plain"))
                {
                    out.println("<p>");
                    IO.copy(p.getInputStream(),out);
                    out.println("</p>");
                }
            } 
            out.println("</body>");            
            out.println("</html>");
            out.flush();
        }
        catch (ServletException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {      
        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<body>");
            out.println("<h1>Use a POST Instead</h1>");
            out.println("</body>");            
            out.println("</html>");
            out.flush();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }
    

  
   
}
