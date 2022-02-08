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

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

public class UpgradeHttpServletResponse implements HttpServletResponse
{
    private static final String UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE = "Feature unsupported after Upgraded to WebSocket";

    private HttpServletResponse _response;
    private int _status;
    private Map<String, Collection<String>> _headers;
    private Locale _locale;
    private String _characterEncoding;
    private String _contentType;

    public UpgradeHttpServletResponse(HttpServletResponse response)
    {
        _response = response;
    }

    public void upgrade()
    {
        _status = _response.getStatus();
        _locale = _response.getLocale();
        _characterEncoding = _response.getCharacterEncoding();
        _contentType = _response.getContentType();
        _headers = new HashMap<>();
        for (String name : _response.getHeaderNames())
        {
            _headers.put(name, _response.getHeaders(name));
        }

        _response = null;
    }

    public HttpServletResponse getResponse()
    {
        return _response;
    }

    @Override
    public int getStatus()
    {
        if (_response == null)
            return _status;
        return _response.getStatus();
    }

    @Override
    public String getHeader(String s)
    {
        if (_response == null)
        {
            Collection<String> values = _headers.get(s);
            if (values == null)
                return null;
            return values.stream().findFirst().orElse(null);
        }

        return _response.getHeader(s);
    }

    @Override
    public Collection<String> getHeaders(String s)
    {
        if (_response == null)
            return _headers.get(s);
        return _response.getHeaders(s);
    }

    @Override
    public Collection<String> getHeaderNames()
    {
        if (_response == null)
            return _headers.keySet();
        return _response.getHeaderNames();
    }

    @Override
    public Locale getLocale()
    {
        if (_response == null)
            return _locale;
        return _response.getLocale();
    }

    @Override
    public boolean containsHeader(String s)
    {
        if (_response == null)
        {
            Collection<String> values = _headers.get(s);
            return values != null && !values.isEmpty();
        }

        return _response.containsHeader(s);
    }

    @Override
    public String getCharacterEncoding()
    {
        if (_response == null)
            return _characterEncoding;
        return _response.getCharacterEncoding();
    }

    @Override
    public String getContentType()
    {
        if (_response == null)
            return _contentType;
        return _response.getContentType();
    }

    @Override
    public void addCookie(Cookie cookie)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.addCookie(cookie);
    }

    @Override
    public String encodeURL(String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.encodeURL(s);
    }

    @Override
    public String encodeRedirectURL(String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.encodeRedirectURL(s);
    }

    @Override
    public String encodeUrl(String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.encodeUrl(s);
    }

    @Override
    public String encodeRedirectUrl(String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.encodeRedirectUrl(s);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.getWriter();
    }

    @Override
    public void setCharacterEncoding(String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setCharacterEncoding(s);
    }

    @Override
    public void setContentLength(int i)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setContentLength(i);
    }

    @Override
    public void setContentLengthLong(long l)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setContentLengthLong(l);
    }

    @Override
    public void setContentType(String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setContentType(s);
    }

    @Override
    public void setBufferSize(int i)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setBufferSize(i);
    }

    @Override
    public int getBufferSize()
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.getBufferSize();
    }

    @Override
    public void flushBuffer() throws IOException
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.flushBuffer();
    }

    @Override
    public void resetBuffer()
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.resetBuffer();
    }

    @Override
    public boolean isCommitted()
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return _response.isCommitted();
    }

    @Override
    public void reset()
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.reset();
    }

    @Override
    public void setLocale(Locale locale)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setLocale(locale);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.sendError(sc);
    }

    @Override
    public void setHeader(String name, String value)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setHeader(name, value);
    }

    @Override
    public void sendRedirect(String s) throws IOException
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.sendRedirect(s);
    }

    @Override
    public void setDateHeader(String s, long l)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setDateHeader(s, l);
    }

    @Override
    public void addDateHeader(String s, long l)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.addDateHeader(s, l);
    }

    @Override
    public void addHeader(String name, String value)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.addHeader(name, value);
    }

    @Override
    public void setIntHeader(String s, int i)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setIntHeader(s, i);
    }

    @Override
    public void addIntHeader(String s, int i)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.addIntHeader(s, i);
    }

    @Override
    public void setStatus(int i)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setStatus(i);
    }

    @Override
    public void setStatus(int i, String s)
    {
        if (_response == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        _response.setStatus(i, s);
    }
}
