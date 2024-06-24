//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The default Servlet, normally mapped to {@code /}, that handles static resources.</p>
 * <p>The following init parameters are supported:</p>
 * <dl>
 *   <dt>acceptRanges</dt>
 *   <dd>
 *     Use {@code true} to accept range requests, defaults to {@code true}.
 *   </dd>
 *   <dt>baseResource</dt>
 *   <dd>
 *     Defaults to the context's baseResource.
 *     The root directory to look for static resources.
 *   </dd>
 *   <dt>cacheControl</dt>
 *   <dd>
 *     The value of the {@code Cache-Control} header.
 *     If omitted, no {@code Cache-Control} header is generated in responses.
 *     By default is omitted.
 *   </dd>
 *   <dt>cacheValidationTime</dt>
 *   <dd>
 *     How long in milliseconds a resource is cached.
 *     If omitted, defaults to {@code 1000} ms.
 *     Use {@code -1} to cache forever or {@code 0} to not cache.
 *   </dd>
 *   <dt>dirAllowed</dt>
 *   <dd>
 *     Use {@code true} to serve directory listing if no welcome file is found.
 *     Otherwise responds with {@code 403 Forbidden}.
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>encodingHeaderCacheSize</dt>
 *   <dd>
 *     Max number of cached {@code Accept-Encoding} entries.
 *     Use {@code -1} for the default value (100), {@code 0} for no cache.
 *   </dd>
 *   <dt>etags</dt>
 *   <dd>
 *     Use {@code true} to generate ETags in responses.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>maxCachedFiles</dt>
 *   <dd>
 *     The max number of cached static resources.
 *     Use {@code -1} for the default value (2048) or {@code 0} for no cache.
 *   </dd>
 *   <dt>maxCachedFileSize</dt>
 *   <dd>
 *     The max size in bytes of a single cached static resource.
 *     Use {@code -1} for the default value (128 MiB) or {@code 0} for no cache.
 *   </dd>
 *   <dt>maxCacheSize</dt>
 *   <dd>
 *     The max size in bytes of the cache for static resources.
 *     Use {@code -1} for the default value (256 MiB) or {@code 0} for no cache.
 *   </dd>
 *   <dt>otherGzipFileExtensions</dt>
 *   <dd>
 *     A comma-separated list of extensions of files whose content is implicitly
 *     gzipped.
 *     Defaults to {@code .svgz}.
 *   </dd>
 *   <dt>precompressed</dt>
 *   <dd>
 *     Omitted by default, so that no pre-compressed content will be served.
 *     If set to {@code true}, the default set of pre-compressed formats will be used.
 *     Otherwise can be set to a comma-separated list of {@code encoding=extension} pairs,
 *     such as: {@code br=.br,gzip=.gz,bzip2=.bz}, where {@code encoding} is used as the
 *     value for the {@code Content-Encoding} header.
 *   </dd>
 *   <dt>redirectWelcome</dt>
 *   <dd>
 *     Use {@code true} to redirect welcome files, otherwise they are forwarded.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>stylesheet</dt>
 *   <dd>
 *     Defaults to the {@code Server}'s default stylesheet, {@code jetty-dir.css}.
 *     The path of a custom stylesheet to style the directory listing HTML.
 *   </dd>
 *   <dt>useFileMappedBuffer</dt>
 *   <dd>
 *     Use {@code true} to use file mapping to serve static resources.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>welcomeServlets</dt>
 *   <dd>
 *     Use {@code false} to only serve welcome resources from the file system.
 *     Use {@code true} to dispatch welcome resources to a matching Servlet
 *     (for example mapped to {@code *.welcome}), when the welcome resources
 *     does not exist on file system.
 *     Use {@code exact} to dispatch welcome resource to a Servlet whose mapping
 *     is exactly the same as the welcome resource (for example {@code /index.welcome}),
 *     when the welcome resources does not exist on file system.
 *     Defaults to {@code false}.
 *   </dd>
 * </dl>
 */
public class DefaultServlet extends ResourceServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServlet.class);
    public static final String CONTEXT_INIT = "org.eclipse.jetty.servlet.Default.";
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /**
     * <p>
     *     Returns a {@code String} containing the value of the named initialization parameter, or null if the parameter does not exist.
     * </p>
     *
     * <p>
     *     Parameter lookup first checks the {@link ServletContext#getInitParameter(String)} for the
     *     parameter prefixed with {@code org.eclipse.jetty.servlet.Default.}, then checks
     *     {@link jakarta.servlet.ServletConfig#getInitParameter(String)} for the actual value
     * </p>
     *
     * @param name a {@code String} specifying the name of the initialization parameter
     * @return a {@code String} containing the value of the initialization parameter
     */
    @Override
    public String getInitParameter(String name)
    {
        String value = getServletContext().getInitParameter(CONTEXT_INIT + name);
        if (value == null)
            value = super.getInitParameter(name);
        return value;
    }

    @Override
    public void init() throws ServletException
    {
        if ("true".equalsIgnoreCase(getInitParameter("pathInfoOnly")))
            LOG.warn("DefaultServlet pathInfoOnly is set to true");
        super.init();
    }

    @Override
    protected String getEncodedPathInContext(HttpServletRequest request, boolean included)
    {
        String deprecatedPath =  getEncodedPathInContext(request, (String)(included ? request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH) : null));
        if (deprecatedPath != null)
            return deprecatedPath;

        if (request.getHttpServletMapping().getMappingMatch() != MappingMatch.DEFAULT)
        {
            if (warned.compareAndSet(false, true))
                LOG.warn("Incorrect mapping for DefaultServlet at %s. Use ResourceServlet".formatted(request.getHttpServletMapping().getPattern()));
            return super.getEncodedPathInContext(request, included);
        }

        if (included)
        {
            if (request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH) instanceof String servletPath)
                return URIUtil.encodePath(servletPath);

            // must be an include of a named dispatcher.  Just use the whole URI
            return URIUtil.encodePath(request.getServletPath());
        }

        if (request instanceof ServletApiRequest apiRequest)
            // Strip the context path from the canonically encoded path, so no need to re-encode (and mess up %2F etc.)
            return Context.getPathInContext(request.getContextPath(), apiRequest.getRequest().getHttpURI().getCanonicalPath());

        return URIUtil.encodePath(request.getServletPath());
    }

    @Deprecated(forRemoval = true)
    protected String getEncodedPathInContext(HttpServletRequest req, String includedServletPath)
    {
        return null;
    }
}
