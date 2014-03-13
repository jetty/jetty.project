//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.proxy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Inspired by nginx's try_files functionality
 */
public class TryFilesFilter implements Filter
{
    public static final String FILES_INIT_PARAM = "files";

    private String[] files;

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        String param = config.getInitParameter(FILES_INIT_PARAM);
        if (param == null)
            throw new ServletException(String.format("Missing mandatory parameter '%s'", FILES_INIT_PARAM));
        files = param.split(" ");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpServletResponse httpResponse = (HttpServletResponse)response;

        for (int i = 0; i < files.length - 1; ++i)
        {
            String file = files[i];
            String resolved = resolve(httpRequest, file);

            URL url = request.getServletContext().getResource(resolved);
            if (url == null)
                continue;

            if (Files.isReadable(toPath(url)))
            {
                chain.doFilter(httpRequest, httpResponse);
                return;
            }
        }

        // The last one is the fallback
        fallback(httpRequest, httpResponse, chain, files[files.length - 1]);
    }

    private Path toPath(URL url) throws IOException
    {
        try
        {
            return Paths.get(url.toURI());
        }
        catch (URISyntaxException x)
        {
            throw new IOException(x);
        }
    }

    protected void fallback(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String fallback) throws IOException, ServletException
    {
        String resolved = resolve(request, fallback);
        request.getServletContext().getRequestDispatcher(resolved).forward(request, response);
    }

    private String resolve(HttpServletRequest request, String value)
    {
        String path = request.getServletPath();
        String info = request.getPathInfo();
        if (info != null)
            path += info;
        if (!path.startsWith("/"))
            path = "/" + path;
        return value.replaceAll("\\$path", path);
    }

    @Override
    public void destroy()
    {
    }
}
