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

package org.eclipse.jetty.ee9.servlets;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Include Exclude Based Filter
 * <p>
 * This is an abstract filter which helps with filtering based on include/exclude of paths, mime types, and/or http methods.
 * <p>
 * Use the {@link #shouldFilter(HttpServletRequest, HttpServletResponse)} method to determine if a request/response should be filtered. If mime types are used,
 * it should be called after {@link jakarta.servlet.FilterChain#doFilter(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse)} since the mime type may not
 * be written until then.
 *
 * Supported init params:
 * <ul>
 * <li><code>includedPaths</code> - CSV of path specs to include</li>
 * <li><code>excludedPaths</code> - CSV of path specs to exclude</li>
 * <li><code>includedMimeTypes</code> - CSV of mime types to include</li>
 * <li><code>excludedMimeTypes</code> - CSV of mime types to exclude</li>
 * <li><code>includedHttpMethods</code> - CSV of http methods to include</li>
 * <li><code>excludedHttpMethods</code> - CSV of http methods to exclude</li>
 * </ul>
 * <p>
 * Path spec rules:
 * <ul>
 * <li>If the spec starts with <code>'^'</code> the spec is assumed to be a regex based path spec and will match with normal Java regex rules.</li>
 * <li>If the spec starts with <code>'/'</code> the spec is assumed to be a Servlet url-pattern rules path spec for either an exact match or prefix based
 * match.</li>
 * <li>If the spec starts with <code>'*.'</code> the spec is assumed to be a Servlet url-pattern rules path spec for a suffix based match.</li>
 * <li>All other syntaxes are unsupported.</li>
 * </ul>
 * <p>
 * CSVs are parsed with {@link StringUtil#csvSplit(String)}
 *
 * @see PathSpecSet
 * @see IncludeExcludeSet
 */
public abstract class IncludeExcludeBasedFilter implements Filter
{
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();
    private final IncludeExclude<String> _httpMethods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private static final Logger LOG = LoggerFactory.getLogger(IncludeExcludeBasedFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        final String includedPaths = filterConfig.getInitParameter("includedPaths");
        final String excludedPaths = filterConfig.getInitParameter("excludedPaths");
        final String includedMimeTypes = filterConfig.getInitParameter("includedMimeTypes");
        final String excludedMimeTypes = filterConfig.getInitParameter("excludedMimeTypes");
        final String includedHttpMethods = filterConfig.getInitParameter("includedHttpMethods");
        final String excludedHttpMethods = filterConfig.getInitParameter("excludedHttpMethods");

        if (includedPaths != null)
        {
            _paths.include(StringUtil.csvSplit(includedPaths));
        }
        if (excludedPaths != null)
        {
            _paths.exclude(StringUtil.csvSplit(excludedPaths));
        }
        if (includedMimeTypes != null)
        {
            _mimeTypes.include(StringUtil.csvSplit(includedMimeTypes));
        }
        if (excludedMimeTypes != null)
        {
            _mimeTypes.exclude(StringUtil.csvSplit(excludedMimeTypes));
        }
        if (includedHttpMethods != null)
        {
            _httpMethods.include(StringUtil.csvSplit(includedHttpMethods));
        }
        if (excludedHttpMethods != null)
        {
            _httpMethods.exclude(StringUtil.csvSplit(excludedHttpMethods));
        }
    }

    protected String guessMimeType(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
    {
        String contentType = httpResponse.getContentType();
        LOG.debug("Content Type is: {}", contentType);

        String mimeType = "";
        if (contentType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(contentType);
            LOG.debug("Mime Type is: {}", mimeType);
        }
        else
        {
            String requestUrl = httpRequest.getPathInfo();
            httpRequest.getServletContext().getMimeType(requestUrl);

            LOG.debug("Guessed mime type is {}", mimeType);
        }

        return mimeType;
    }

    protected boolean shouldFilter(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
    {
        String httpMethod = httpRequest.getMethod();
        LOG.debug("HTTP method is: {}", httpMethod);
        if (!_httpMethods.test(httpMethod))
        {
            LOG.debug("should not apply filter because HTTP method does not match");
            return false;
        }

        String mimeType = guessMimeType(httpRequest, httpResponse);

        if (!_mimeTypes.test(mimeType))
        {
            LOG.debug("should not apply filter because mime type does not match");
            return false;
        }

        ServletContext context = httpRequest.getServletContext();
        String path = context == null ? httpRequest.getRequestURI() : URIUtil.addPaths(httpRequest.getServletPath(), httpRequest.getPathInfo());
        LOG.debug("Path is: {}", path);
        if (!_paths.test(path))
        {
            LOG.debug("should not apply filter because path does not match");
            return false;
        }

        return true;
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("filter configuration:\n");
        sb.append("paths:\n").append(_paths).append("\n");
        sb.append("mime types:\n").append(_mimeTypes).append("\n");
        sb.append("http methods:\n").append(_httpMethods);
        return sb.toString();
    }
}
