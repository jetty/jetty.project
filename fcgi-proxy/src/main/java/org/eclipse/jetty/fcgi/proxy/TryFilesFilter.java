//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
    public static final String ROOT_INIT_PARAM = "root";
    public static final String FILES_INIT_PARAM = "files";

    private String root;
    private String[] files;

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        root = config.getInitParameter(ROOT_INIT_PARAM);
        if (root == null)
            throw new ServletException(String.format("Missing mandatory parameter '%s'", ROOT_INIT_PARAM));
        String param = config.getInitParameter(FILES_INIT_PARAM);
        if (param == null)
            throw new ServletException(String.format("Missing mandatory parameter '%s'", FILES_INIT_PARAM));
        files = param.split(" ");
    }

    public String getRoot()
    {
        return root;
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
            Path path = Paths.get(getRoot(), resolved);
            if (Files.exists(path) && Files.isReadable(path))
            {
                chain.doFilter(httpRequest, httpResponse);
                return;
            }
        }

        // The last one is the fallback
        fallback(httpRequest, httpResponse, chain, files[files.length - 1]);
    }

    protected void fallback(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String fallback) throws IOException, ServletException
    {
        String resolved = resolve(request, fallback);
        request.getRequestDispatcher(resolved).forward(request, response);
    }

    private String resolve(HttpServletRequest request, String value)
    {
        String path = request.getRequestURI();
        String query = request.getQueryString();

        String result = value.replaceAll("\\$path", path);
        result = result.replaceAll("\\$query", query == null ? "" : query);

        // Remove the "?" or "&" at the end if there is no query
        if (query == null && (result.endsWith("?") || result.endsWith("&")))
            result = result.substring(0, result.length() - 1);

        return result;
    }

    @Override
    public void destroy()
    {
    }
}
