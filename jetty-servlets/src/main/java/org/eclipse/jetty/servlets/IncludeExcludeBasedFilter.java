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

package org.eclipse.jetty.servlets;

import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Include Exclude Based Filter
 * <p>
 * This is an abstract filter which helps with filtering based on include/exclude of paths, mime types, and/or http methods.
 * <p>
 * Use the {@link #shouldFilter(HttpServletRequest, HttpServletResponse)} method to determine if a request/response should be filtered. If mime types are used,
 * it should be called after {@link javax.servlet.FilterChain#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse)} since the mime type may not
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
    private static final Logger LOG = Log.getLogger(IncludeExcludeBasedFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        String included_paths = filterConfig.getInitParameter("includedPaths");
        String excluded_paths = filterConfig.getInitParameter("excludedPaths");
        String included_mime_types = filterConfig.getInitParameter("includedMimeTypes");
        String excluded_mime_types = filterConfig.getInitParameter("excludedMimeTypes");
        String included_http_methods = filterConfig.getInitParameter("includedHttpMethods");
        String excluded_http_methods = filterConfig.getInitParameter("excludedHttpMethods");

        if (included_paths != null)
        {
            _paths.include(StringUtil.csvSplit(included_paths));
        }
        if (excluded_paths != null)
        {
            _paths.exclude(StringUtil.csvSplit(excluded_paths));
        }
        if (included_mime_types != null)
        {
            _mimeTypes.include(StringUtil.csvSplit(included_mime_types));
        }
        if (excluded_mime_types != null)
        {
            _mimeTypes.exclude(StringUtil.csvSplit(excluded_mime_types));
        }
        if (included_http_methods != null)
        {
            _httpMethods.include(StringUtil.csvSplit(included_http_methods));
        }
        if (excluded_http_methods != null)
        {
            _httpMethods.exclude(StringUtil.csvSplit(excluded_http_methods));
        }
    }

    protected String guessMimeType(HttpServletRequest http_request, HttpServletResponse http_response)
    {
        String content_type = http_response.getContentType();
        LOG.debug("Content Type is: {}",content_type);

        String mime_type = "";
        if (content_type != null)
        {
            mime_type = MimeTypes.getContentTypeWithoutCharset(content_type);
            LOG.debug("Mime Type is: {}",mime_type);
        }
        else
        {
            String request_url = http_request.getPathInfo();
            mime_type = MimeTypes.getDefaultMimeByExtension(request_url);

            if (mime_type == null)
            {
                mime_type = "";
            }

            LOG.debug("Guessed mime type is {}",mime_type);
        }

        return mime_type;
    }

    protected boolean shouldFilter(HttpServletRequest http_request, HttpServletResponse http_response)
    {
        String http_method = http_request.getMethod();
        LOG.debug("HTTP method is: {}",http_method);
        if (!_httpMethods.test(http_method))
        {
            LOG.debug("should not apply filter because HTTP method does not match");
            return false;
        }

        String mime_type = guessMimeType(http_request,http_response);

        if (!_mimeTypes.test(mime_type))
        {
            LOG.debug("should not apply filter because mime type does not match");
            return false;
        }

        ServletContext context = http_request.getServletContext();
        String path = context == null?http_request.getRequestURI():URIUtil.addPaths(http_request.getServletPath(),http_request.getPathInfo());
        LOG.debug("Path is: {}",path);
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
