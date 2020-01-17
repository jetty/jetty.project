//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
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
        setAllowNullPathInfo(true);
    }

    public MovedContextHandler(HandlerContainer parent, String contextPath, String newContextURL)
    {
        super(parent, contextPath);
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

    private class Redirector extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (_newContextURL == null)
                return;

            String path = _newContextURL;
            if (!_discardPathInfo && request.getPathInfo() != null)
                path = URIUtil.addPaths(path, request.getPathInfo());

            StringBuilder location = URIUtil.hasScheme(path) ? new StringBuilder() : baseRequest.getRootURL();

            location.append(path);
            if (!_discardQuery && request.getQueryString() != null)
            {
                location.append('?');
                String q = request.getQueryString();
                q = q.replaceAll("\r\n?&=", "!");
                location.append(q);
            }

            response.setHeader(HttpHeader.LOCATION.asString(), location.toString());

            if (_expires != null)
                response.setHeader(HttpHeader.EXPIRES.asString(), _expires);

            response.setStatus(_permanent ? HttpServletResponse.SC_MOVED_PERMANENTLY : HttpServletResponse.SC_FOUND);
            response.setContentLength(0);
            baseRequest.setHandled(true);
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
