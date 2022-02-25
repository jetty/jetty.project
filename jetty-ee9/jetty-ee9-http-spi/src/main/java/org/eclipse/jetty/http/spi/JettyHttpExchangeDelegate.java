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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Jetty implementation of {@link com.sun.net.httpserver.HttpExchange}
 */
public class JettyHttpExchangeDelegate extends HttpExchange
{

    private HttpContext _httpContext;

    private HttpServletRequest _req;

    private HttpServletResponse _resp;

    private Headers _responseHeaders = new Headers();

    private int _responseCode = 0;

    private InputStream _is;

    private OutputStream _os;

    private HttpPrincipal _httpPrincipal;

    JettyHttpExchangeDelegate(HttpContext jaxWsContext, HttpServletRequest req, HttpServletResponse resp)
    {
        this._httpContext = jaxWsContext;
        this._req = req;
        this._resp = resp;
        try
        {
            this._is = req.getInputStream();
            this._os = resp.getOutputStream();
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Headers getRequestHeaders()
    {
        Headers headers = new Headers();
        Enumeration<?> en = _req.getHeaderNames();
        while (en.hasMoreElements())
        {
            String name = (String)en.nextElement();
            Enumeration<?> en2 = _req.getHeaders(name);
            while (en2.hasMoreElements())
            {
                String value = (String)en2.nextElement();
                headers.add(name, value);
            }
        }
        return headers;
    }

    @Override
    public Headers getResponseHeaders()
    {
        return _responseHeaders;
    }

    @Override
    public URI getRequestURI()
    {
        try
        {
            String uriAsString = _req.getRequestURI();
            if (_req.getQueryString() != null)
            {
                uriAsString += "?" + _req.getQueryString();
            }

            return new URI(uriAsString);
        }
        catch (URISyntaxException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public String getRequestMethod()
    {
        return _req.getMethod();
    }

    @Override
    public HttpContext getHttpContext()
    {
        return _httpContext;
    }

    @Override
    public void close()
    {
        try
        {
            _resp.getOutputStream().close();
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public InputStream getRequestBody()
    {
        return _is;
    }

    @Override
    public OutputStream getResponseBody()
    {
        return _os;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException
    {
        this._responseCode = rCode;

        for (Map.Entry<String, List<String>> stringListEntry : _responseHeaders.entrySet())
        {
            String name = stringListEntry.getKey();
            List<String> values = stringListEntry.getValue();

            for (String value : values)
            {
                _resp.setHeader(name, value);
            }
        }
        if (responseLength > 0)
        {
            _resp.setHeader("content-length", "" + responseLength);
        }
        _resp.setStatus(rCode);
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return new InetSocketAddress(_req.getRemoteAddr(), _req.getRemotePort());
    }

    @Override
    public int getResponseCode()
    {
        return _responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return new InetSocketAddress(_req.getLocalAddr(), _req.getLocalPort());
    }

    @Override
    public String getProtocol()
    {
        return _req.getProtocol();
    }

    @Override
    public Object getAttribute(String name)
    {
        return _req.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        _req.setAttribute(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o)
    {
        _is = i;
        _os = o;
    }

    @Override
    public HttpPrincipal getPrincipal()
    {
        return _httpPrincipal;
    }

    public void setPrincipal(HttpPrincipal principal)
    {
        this._httpPrincipal = principal;
    }
}
