//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.server.proxy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.StringUtil;

/**
 * Inspired by nginx's try_files functionality.
 * <p>
 * This filter accepts the {@code files} init-param as a list of space-separated
 * file URIs. The special token {@code $path} represents the current request URL's
 * path (the portion after the context path).
 * <p>
 * Typical example of how this filter can be configured is the following:
 * <pre>
 * &lt;filter&gt;
 *     &lt;filter-name&gt;try_files&lt;/filter-name&gt;
 *     &lt;filter-class&gt;org.eclipse.jetty.fcgi.server.proxy.TryFilesFilter&lt;/filter-class&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;files&lt;/param-name&gt;
 *         &lt;param-value&gt;/maintenance.html $path /index.php?p=$path&lt;/param-value&gt;
 *     &lt;/init-param&gt;
 * &lt;/filter&gt;
 * </pre>
 * For a request such as {@code /context/path/to/resource.ext}, this filter will
 * try to serve the {@code /maintenance.html} file if it finds it; failing that,
 * it will try to serve the {@code /path/to/resource.ext} file if it finds it;
 * failing that it will forward the request to {@code /index.php?p=/path/to/resource.ext}.
 * The last file URI specified in the list is therefore the "fallback" to which the request
 * is forwarded to in case no previous files can be found.
 * <p>
 * The files are resolved using {@link ServletContext#getResource(String)} to make sure
 * that only files visible to the application are served.
 *
 * @see FastCGIProxyServlet
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
        return StringUtil.replace(value, "$path", path);
    }

    @Override
    public void destroy()
    {
    }
}
