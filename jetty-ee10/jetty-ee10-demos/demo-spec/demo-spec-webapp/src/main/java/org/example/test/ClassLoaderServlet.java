//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.example.test;

import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
            writer.println("<head><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></head>");
            writer.println("<body>");
            writer.println("<h1>ClassLoader Isolation Test</h1>");

            // TODO uncomment the following once 9.4.19 is released with a fix for #3726
            /*
            Class<?> webappIO = IO.class;
            URI webappURI = getLocationOfClass(webappIO);
            String webappVersion = webappIO.getPackage().getImplementationVersion();
            Class<?> serverIO = req.getServletContext().getClass().getClassLoader().loadClass("org.eclipse.jetty.util.IO");
            URI serverURI = getLocationOfClass(serverIO);
            String serverVersion = serverIO.getPackage().getImplementationVersion();

            writer.printf("<p>Webapp loaded <code>org.eclipse.jetty.util.IO</code>(%s) from %s%n",webappVersion,webappURI);
            writer.printf("<br/>Server loaded <code>org.eclipse.jetty.util.IO</code>(%s) from %s%n",serverVersion, serverURI);
            if (webappVersion.equals(serverVersion))
                writer.println("<br/><b>Version Result: <span class=\"fail\">FAIL</span></b>");
            else
                writer.println("<br/><b>Version Result: <span class=\"pass\">PASS</span></b>");
            if (webappURI.equals(serverURI))
                writer.println("<br/><b>URI Result: <span class=\"fail\">FAIL</span></b></p>");
            else
                writer.println("<br/><b>URI Result: <span class=\"pass\">PASS</span></b></p>");
            */

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
