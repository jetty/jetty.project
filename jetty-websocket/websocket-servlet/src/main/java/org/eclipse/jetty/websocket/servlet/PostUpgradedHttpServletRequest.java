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

package org.eclipse.jetty.websocket.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Collection;

import javax.servlet.AsyncContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

public class PostUpgradedHttpServletRequest extends HttpServletRequestWrapper
{
    private static final String UNSUPPORTED_WITH_WEBSOCKET_UPGRADE = "Feature unsupported with a Upgraded to WebSocket HttpServletRequest";

    public PostUpgradedHttpServletRequest(HttpServletRequest request)
    {
        super(request);
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String changeSessionId()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String getCharacterEncoding()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public int getContentLength()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public long getContentLengthLong()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String getContentType()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public BufferedReader getReader() throws IOException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public ServletRequest getRequest()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isAsyncStarted()
    {
        return false;
    }

    @Override
    public boolean isAsyncSupported()
    {
        return false;
    }

    @Override
    public boolean isWrapperFor(Class<?> wrappedType)
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isWrapperFor(ServletRequest wrapped)
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public void logout() throws ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public void setCharacterEncoding(String enc) throws UnsupportedEncodingException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public void setRequest(ServletRequest request)
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }
}
