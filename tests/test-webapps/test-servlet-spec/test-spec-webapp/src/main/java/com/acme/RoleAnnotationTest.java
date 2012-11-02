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

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.annotation.security.DeclareRoles;

/**
 * RoleAnnotationTest
 * 
 * Use DeclareRolesAnnotations from within Jetty.
 * 
 *
 */


@DeclareRoles({"server-administrator","user"})
public class RoleAnnotationTest extends HttpServlet 
{
    private ServletConfig _config;
    
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        _config = config;
    }

    
    
    /* ------------------------------------------------------------ */
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    /* ------------------------------------------------------------ */
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {      
        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<h1>Jetty DeclareRoles Annotation Results</h1>");
            out.println("<body>");
            
            out.println("<h2>Roles</h2>");
            boolean result = request.isUserInRole("other");
            out.println("<br/><b>Result: isUserInRole(\"other\")="+result+":"+ (result==false?" PASS":" FAIL")+"</b>");

            result = request.isUserInRole("manager");
            out.println("<br/><b>Result: isUserInRole(\"manager\")="+result+":"+ (result?" PASS":" FAIL")+"</b>");
            result = request.isUserInRole("user");
            out.println("<br/><b>Result: isUserInRole(\"user\")="+result+":"+ (result==false?" PASS":" FAIL")+"</b>");
            String context = _config.getServletContext().getContextPath();
            if (!context.endsWith("/"))
                context += "/";
            
            out.println("<p><A HREF=\""+context+"logout.jsp\">Logout</A></p>");
            
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
