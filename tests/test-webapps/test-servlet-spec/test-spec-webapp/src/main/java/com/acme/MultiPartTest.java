//========================================================================
//Copyright 2010 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

/**
 * 
 */
package com.acme;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.servlet.annotation.MultipartConfig;

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
    
    
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        this.config = config;
    }

    
    
    /* ------------------------------------------------------------ */
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {

        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<h1>Results</h1>");
            out.println("<body>");
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
