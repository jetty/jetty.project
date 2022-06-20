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

import java.io.IOException;
import java.util.Collection;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

/**
 * MultiPartTest
 *
 * Test Servlet 3.0 MultiPart Mime handling.
 */

@MultipartConfig(location = "foo/bar", maxFileSize = 10240, maxRequestSize = -1, fileSizeThreshold = 2048)
public class MultiPartTest extends HttpServlet
{
    private ServletConfig config;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        this.config = config;
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        try
        {
            response.setContentType("text/html");
            ServletOutputStream out = response.getOutputStream();
            out.println("<html>");
            out.println("<head><link rel=\"stylesheet\" type=\"text/css\"  href=\"stylesheet.css\"/></head>");
            out.println("<body>");
            out.println("<h1>Results</h1>");
            out.println("<p>");

            Collection<Part> parts = request.getParts();
            out.println("<b>Parts:</b>&nbsp;" + parts.size() + "<br>");
            for (Part p : parts)
            {
                out.println("<br><b>PartName:</b>&nbsp;" + sanitizeXmlString(p.getName()));
                out.println("<br><b>Size:</b>&nbsp;" + p.getSize());
                String contentType = p.getContentType();
                out.println("<br><b>ContentType:</b>&nbsp;" + contentType);
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

    public static String sanitizeXmlString(String html)
    {
        if (html == null)
            return null;

        int i = 0;

        // Are there any characters that need sanitizing?
        loop:
        for (; i < html.length(); i++)
        {
            char c = html.charAt(i);
            switch (c)
            {
                case '&':
                case '<':
                case '>':
                case '\'':
                case '"':
                    break loop;
                default:
                    if (Character.isISOControl(c) && !Character.isWhitespace(c))
                        break loop;
            }
        }
        // No characters need sanitizing, so return original string
        if (i == html.length())
            return html;

        // Create builder with OK content so far
        StringBuilder out = new StringBuilder(html.length() * 4 / 3);
        out.append(html, 0, i);

        // sanitize remaining content
        for (; i < html.length(); i++)
        {
            char c = html.charAt(i);
            switch (c)
            {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                default:
                    if (Character.isISOControl(c) && !Character.isWhitespace(c))
                        out.append('?');
                    else
                        out.append(c);
            }
        }
        return out.toString();
    }
}
