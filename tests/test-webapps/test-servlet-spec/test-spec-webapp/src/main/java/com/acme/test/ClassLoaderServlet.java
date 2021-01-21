//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.IO;

@WebServlet(urlPatterns = "/classloader")
public class ClassLoaderServlet extends HttpServlet
{
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException
    {
        try
        {
            PrintWriter writer = resp.getWriter();
            writer.println("<html>");
            writer.println("<HEAD><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></HEAD>");
            writer.println("<body>");
            writer.println("<h1>ClassLoader Isolation Test</h1>");

            Class<?> webappIO = IO.class;
            URI webappURI = getLocationOfClass(webappIO);
            String webappVersion = webappIO.getPackage().getImplementationVersion();
            Class<?> serverIO = req.getServletContext().getClass().getClassLoader().loadClass("org.eclipse.jetty.util.IO");
            URI serverURI = getLocationOfClass(serverIO);
            String serverVersion = serverIO.getPackage().getImplementationVersion();

            writer.printf("<p>Webapp loaded <code>org.eclipse.jetty.util.IO</code>(%s) from %s%n", webappVersion, webappURI);
            writer.printf("<br/>Server loaded <code>org.eclipse.jetty.util.IO</code>(%s) from %s%n", serverVersion, serverURI);
            if (webappVersion.equals(serverVersion))
                writer.println("<br/><b>Version Result: <span class=\"fail\">FAIL</span></b>");
            else
                writer.println("<br/><b>Version Result: <span class=\"pass\">PASS</span></b>");
            if (webappURI.equals(serverURI))
                writer.println("<br/><b>URI Result: <span class=\"fail\">FAIL</span></b></p>");
            else
                writer.println("<br/><b>URI Result: <span class=\"pass\">PASS</span></b></p>");

            writer.println("</body>");
            writer.println("</html>");
            writer.flush();
            writer.close();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    public static URI getLocationOfClass(Class<?> clazz)
    {
        try
        {
            ProtectionDomain domain = clazz.getProtectionDomain();
            if (domain != null)
            {
                CodeSource source = domain.getCodeSource();
                if (source != null)
                {
                    URL location = source.getLocation();

                    if (location != null)
                        return location.toURI();
                }
            }

            String resourceName = clazz.getName().replace('.', '/') + ".class";
            ClassLoader loader = clazz.getClassLoader();
            URL url = (loader == null ? ClassLoader.getSystemClassLoader() : loader).getResource(resourceName);
            if (url != null)
            {
                return getJarSource(url.toURI());
            }
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
        return null;
    }

    public static URI getJarSource(URI uri)
    {
        try
        {
            if (!"jar".equals(uri.getScheme()))
                return uri;
            // Get SSP (retaining encoded form)
            String s = uri.getRawSchemeSpecificPart();
            int bangSlash = s.indexOf("!/");
            if (bangSlash >= 0)
                s = s.substring(0, bangSlash);
            return new URI(s);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }
}
