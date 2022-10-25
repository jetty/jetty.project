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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;

/**
 * Moved ContextHandler.
 * This context can be used to replace a context that has changed
 * location.  Requests are redirected (either to a fixed URL or to a
 * new context base).
 */
public class MovedContextHandler extends ContextHandler
{
    final Redirector _redirector;
    String _newContextURL;
    boolean _discardPathInfo;
    boolean _discardQuery;
    boolean _permanent;
    String _expires;

    public MovedContextHandler()
    {
        _redirector = new Redirector();
        setHandler(_redirector);
        setAllowNullPathInContext(true);
    }

    public MovedContextHandler(Handler.Collection parent, String contextPath, String newContextURL)
    {
        super(contextPath);
        parent.addHandler(this);
        _newContextURL = newContextURL;
        _redirector = new Redirector();
        setHandler(_redirector);
    }

    public boolean isDiscardPathInfo()
    {
        return _discardPathInfo;
    }

    public void setDiscardPathInfo(boolean discardPathInfo)
    {
        _discardPathInfo = discardPathInfo;
    }

    public String getNewContextURL()
    {
        return _newContextURL;
    }

    public void setNewContextURL(String newContextURL)
    {
        _newContextURL = newContextURL;
    }

    public boolean isPermanent()
    {
        return _permanent;
    }

    public void setPermanent(boolean permanent)
    {
        _permanent = permanent;
    }

    public boolean isDiscardQuery()
    {
        return _discardQuery;
    }

    public void setDiscardQuery(boolean discardQuery)
    {
        _discardQuery = discardQuery;
    }

    private class Redirector extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            if (_newContextURL == null)
                return;

            String path = _newContextURL;
            String pathInContext = Request.getPathInContext(request);
            if (!_discardPathInfo && pathInContext != null)
                path = URIUtil.addPaths(path, pathInContext);

            HttpURI uri = request.getHttpURI();
            StringBuilder location = new StringBuilder();
            location.append(uri.getScheme()).append("://").append(uri.getAuthority()).append(path);

            if (!_discardQuery && uri.getQuery() != null)
            {
                location.append('?');
                String q = uri.getQuery();
                q = q.replaceAll("\r\n?&=", "!");
                location.append(q);
            }

            response.getHeaders().put(HttpHeader.LOCATION, location.toString());
            if (_expires != null)
                response.getHeaders().put(HttpHeader.EXPIRES, _expires);
            response.setStatus(_permanent ? HttpStatus.MOVED_PERMANENTLY_301 : HttpStatus.FOUND_302);
            callback.succeeded();
        }
    }

    /**
     * @return the expires header value or null if no expires header
     */
    public String getExpires()
    {
        return _expires;
    }

    /**
     * @param expires the expires header value or null if no expires header
     */
    public void setExpires(String expires)
    {
        _expires = expires;
    }
}
