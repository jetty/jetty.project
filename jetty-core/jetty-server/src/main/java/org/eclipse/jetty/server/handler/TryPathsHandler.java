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

package org.eclipse.jetty.server.handler;

import java.util.List;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Inspired by nginx's {@code try_files} functionality.</p>
 *
 * <p>This handler can be configured with a list of rewrite URI paths.
 * The special token {@code $path} represents the current request
 * {@code pathInContext} (the portion after the context path).</p>
 *
 * <p>Typical example of how this handler can be configured is the following:</p>
 * <pre>{@code
 * TryPathsHandler tryPathsHandler = new TryPathsHandler();
 * tryPathsHandler.setPaths("/maintenance.html", "$path", "/index.php?p=$path");
 *
 * PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
 * tryPathsHandler.setHandler(pathMappingsHandler);
 *
 * pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), new PHPHandler());
 * pathMappingsHandler.addMapping(new ServletPathSpec("/"), new ResourceHandler());
 * }</pre>
 *
 * <p>For a request such as {@code /context/path/to/resource.ext}:</p>
 * <ul>
 * <li>This handler rewrites the request {@code pathInContext} to
 * {@code /maintenance.html} and forwards the request to the next handler,
 * where it matches the {@code /} mapping, hitting the {@code ResourceHandler}
 * that serves the file if it exists.</li>
 * <li>Otherwise, this handler rewrites the request {@code pathInContext} to
 * {@code /path/to/resource.ext} and forwards the request to the next handler,
 * where it matches the {@code /} mapping, hitting the {@code ResourceHandler}
 * that serves the file if it exists.</li>
 * <li>Otherwise, this handler rewrites the request {@code pathInContext} to
 * {@code /index.php?p=/path/to/resource.ext} and forwards the request to
 * the next handler, where it matches the {@code *.php} mapping, hitting
 * the {@code PHPHandler}.</li>
 * </ul>
 *
 * <p>The original path and query may be stored as request attributes,
 * under the names specified by {@link #setOriginalPathAttribute(String)}
 * and {@link #setOriginalQueryAttribute(String)}.</p>
 */
public class TryPathsHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(TryPathsHandler.class);

    private String originalPathAttribute;
    private String originalQueryAttribute;
    private List<String> paths;

    public TryPathsHandler()
    {
        this(null);
    }

    public TryPathsHandler(Handler handler)
    {
        super(handler);
    }

    /**
     * @return the attribute name of the original request path
     */
    public String getOriginalPathAttribute()
    {
        return originalPathAttribute;
    }

    /**
     * <p>Sets the request attribute name to use to
     * retrieve the original request path.</p>
     *
     * @param originalPathAttribute the attribute name of the original
     * request path
     */
    public void setOriginalPathAttribute(String originalPathAttribute)
    {
        this.originalPathAttribute = originalPathAttribute;
    }

    /**
     * @return the attribute name of the original request query
     */
    public String getOriginalQueryAttribute()
    {
        return originalQueryAttribute;
    }

    /**
     * <p>Sets the request attribute name to use to
     * retrieve the original request query.</p>
     *
     * @param originalQueryAttribute the attribute name of the original
     * request query
     */
    public void setOriginalQueryAttribute(String originalQueryAttribute)
    {
        this.originalQueryAttribute = originalQueryAttribute;
    }

    /**
     * @return the rewrite URI paths
     */
    public List<String> getPaths()
    {
        return paths;
    }

    /**
     * <p>Sets a list of rewrite URI paths.</p>
     * <p>The special token {@code $path} represents the current request
     * {@code pathInContext} (the portion after the context path).</p>
     *
     * @param paths the rewrite URI paths
     */
    public void setPaths(List<String> paths)
    {
        this.paths = paths;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;
        for (String path : paths)
        {
            String interpolated = interpolate(request, path);
            TryPathsRequest tryRequest = new TryPathsRequest(request, interpolated);
            if (LOG.isDebugEnabled())
                LOG.debug("rewritten request URI {} -> {}", request.getHttpURI(), tryRequest.getHttpURI());
            boolean handled = super.handle(tryRequest, response, callback);
            if (LOG.isDebugEnabled())
                LOG.debug("handled {} {}", handled, tryRequest.getHttpURI());
            if (handled)
                return true;
        }
        return false;
    }

    private String interpolate(Request request, String value)
    {
        String path = Request.getPathInContext(request);
        return value.replace("$path", path);
    }

    private class TryPathsRequest extends Request.Wrapper
    {
        private final HttpURI uri;

        public TryPathsRequest(Request wrapped, String newPathQuery)
        {
            super(wrapped);

            HttpURI originalURI = wrapped.getHttpURI();

            String originalPathAttribute = getOriginalPathAttribute();
            if (originalPathAttribute != null)
            {
                if (getAttribute(originalPathAttribute) == null)
                    setAttribute(originalPathAttribute, Request.getPathInContext(wrapped));
            }
            String originalQueryAttribute = getOriginalQueryAttribute();
            if (originalQueryAttribute != null)
            {
                if (getAttribute(originalQueryAttribute) == null)
                    setAttribute(originalQueryAttribute, originalURI.getQuery());
            }

            String originalContextPath = Request.getContextPath(wrapped);
            HttpURI.Mutable rewrittenURI = HttpURI.build(originalURI);
            int queryIdx = newPathQuery.indexOf('?');
            if (queryIdx >= 0)
            {
                String path = newPathQuery.substring(0, queryIdx);
                rewrittenURI.path(URIUtil.addPaths(originalContextPath, path));
                rewrittenURI.query(newPathQuery.substring(queryIdx + 1));
            }
            else
            {
                rewrittenURI.path(URIUtil.addPaths(originalContextPath, newPathQuery));
            }
            uri = rewrittenURI.asImmutable();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return uri;
        }
    }
}
