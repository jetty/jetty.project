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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>A {@code ContextHandler} with a child {@code Handler}
 * that redirects to a configurable URI.</p>
 */
public class MovedContextHandler extends ContextHandler
{
    private int _statusCode = HttpStatus.SEE_OTHER_303;
    private String _redirectURI;
    private boolean _discardPathInContext = true;
    private boolean _discardQuery = true;
    private HttpField _cacheControl;

    public MovedContextHandler()
    {
        setHandler(new Redirector());
        setAllowNullPathInContext(true);
    }

    public MovedContextHandler(Handler.Collection parent, String contextPath, String redirectURI)
    {
        parent.addHandler(this);
        setContextPath(contextPath);
        setRedirectURI(redirectURI);
    }

    /**
     * @return the redirect status code, by default 303
     */
    public int getStatusCode()
    {
        return _statusCode;
    }

    /**
     * @param statusCode the redirect status code
     * @throws IllegalArgumentException if the status code is not of type redirect (3xx)
     */
    public void setStatusCode(int statusCode)
    {
        if (!HttpStatus.isRedirection(statusCode))
            throw new IllegalArgumentException("Invalid HTTP redirection status code: " + statusCode);
        _statusCode = statusCode;
    }

    /**
     * @return the URI to redirect to
     */
    public String getRedirectURI()
    {
        return _redirectURI;
    }

    /**
     * <p>Sets the URI to redirect to.</p>
     * <p>If the redirect URI is not absolute, the original request scheme
     * and authority will be used to build the redirect URI.</p>
     * <p>The original request {@link Request#getPathInContext(Request) pathInContext}
     * will be appended to the redirect URI path, unless {@link #isDiscardPathInContext()}.</p>
     * <p>The original request query will be preserved in the redirect URI, unless
     * {@link #isDiscardQuery()}.</p>
     *
     * @param redirectURI the URI to redirect to
     */
    public void setRedirectURI(String redirectURI)
    {
        _redirectURI = redirectURI;
    }

    /**
     * @return whether the original request {@code pathInContext} is discarded
     */
    public boolean isDiscardPathInContext()
    {
        return _discardPathInContext;
    }

    /**
     * <p>Whether to discard the original request {@link Request#getPathInContext(Request) pathInContext}
     * when building the redirect URI.</p>
     *
     * @param discardPathInContext whether the original request {@code pathInContext} is discarded
     * @see #setRedirectURI(String)
     */
    public void setDiscardPathInContext(boolean discardPathInContext)
    {
        _discardPathInContext = discardPathInContext;
    }

    /**
     * @return whether the original request query is discarded
     */
    public boolean isDiscardQuery()
    {
        return _discardQuery;
    }

    /**
     * <p>Whether to discard the original request query
     * when building the redirect URI.</p>
     *
     * @param discardQuery whether the original request query is discarded
     */
    public void setDiscardQuery(boolean discardQuery)
    {
        _discardQuery = discardQuery;
    }

    /**
     * @return the {@code Cache-Control} header value or {@code null}
     */
    public String getCacheControl()
    {
        return _cacheControl == null ? null : _cacheControl.getValue();
    }

    /**
     * @param cacheControl the {@code Cache-Control} header value or {@code null}
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = cacheControl == null ? null : new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cacheControl);
    }

    private class Redirector extends Abstract
    {
        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            String redirectURI = getRedirectURI();
            if (redirectURI == null)
                redirectURI = "/";

            HttpURI.Mutable redirectHttpURI = HttpURI.build(redirectURI);
            if (redirectHttpURI.getScheme() == null)
            {
                HttpURI httpURI = request.getHttpURI();
                redirectHttpURI = redirectHttpURI.scheme(httpURI.getScheme())
                    .authority(httpURI.getAuthority());
            }

            if (!isDiscardPathInContext())
            {
                String pathInContext = Request.getPathInContext(request);
                String newPath = redirectHttpURI.getPath();
                redirectHttpURI.path(URIUtil.addPaths(newPath, pathInContext));
            }

            if (!isDiscardQuery())
            {
                String query = request.getHttpURI().getQuery();
                String newQuery = redirectHttpURI.getQuery();
                redirectHttpURI.query(URIUtil.addQueries(query, newQuery));
            }

            response.setStatus(getStatusCode());

            response.getHeaders().put(HttpHeader.LOCATION, redirectHttpURI.asString());

            HttpField cacheControl = _cacheControl;
            if (cacheControl != null)
                response.getHeaders().put(cacheControl);

            callback.succeeded();
            return true;
        }
    }
}
