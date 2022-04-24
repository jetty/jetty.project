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
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * Jetty implementation of {@link com.sun.net.httpserver.HttpExchange}
 */
public class JettyHttpExchangeDelegate extends HttpExchange
{
    private final HttpContext _httpContext;

    private final Request _request;

    private final Response _response;

    private final Headers _responseHeaders = new Headers();

    private int _responseCode = 0;

    private InputStream _inputStream;

    private OutputStream _outputStream;

    private HttpPrincipal _httpPrincipal;

    JettyHttpExchangeDelegate(HttpContext httpSpiContext, Request request, Response response)
    {
        this._httpContext = httpSpiContext;
        this._request = request;
        this._response = response;
        this._inputStream = Content.asInputStream(request);
        this._outputStream = Content.asOutputStream(response);
    }

    @Override
    public Headers getRequestHeaders()
    {
        Headers headers = new Headers();

        for (HttpField field : _request.getHeaders())
        {
            if (field.getValue() == null)
                continue;
            for (String value : field.getValues())
                headers.add(field.getName(), value);
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
        return _request.getHttpURI().toURI();
    }

    @Override
    public String getRequestMethod()
    {
        return _request.getMethod();
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
            _outputStream.close();
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public InputStream getRequestBody()
    {
        return _inputStream;
    }

    @Override
    public OutputStream getResponseBody()
    {
        return _outputStream;
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
                _response.setHeader(name, value);
            }
        }
        if (responseLength > 0)
        {
            _response.setHeader("content-length", "" + responseLength);
        }
        _response.setStatus(rCode);
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = _request.getConnectionMetaData().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress inet)
            return inet;
        return null;
    }

    @Override
    public int getResponseCode()
    {
        return _responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        SocketAddress local = _request.getConnectionMetaData().getLocalSocketAddress();
        if (local instanceof InetSocketAddress inet)
            return inet;
        return null;
    }

    @Override
    public String getProtocol()
    {
        return _request.getConnectionMetaData().getProtocol();
    }

    @Override
    public Object getAttribute(String name)
    {
        return _request.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        _request.setAttribute(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o)
    {
        _inputStream = i;
        _outputStream = o;
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
