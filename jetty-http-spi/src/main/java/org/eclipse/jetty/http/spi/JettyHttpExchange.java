//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

/* ------------------------------------------------------------ */
/**
 */
public class JettyHttpExchange extends HttpExchange implements JettyExchange
{
    private JettyHttpExchangeDelegate _delegate;

    public JettyHttpExchange(HttpContext jaxWsContext, HttpServletRequest req, HttpServletResponse resp)
    {
        super();
        _delegate = new JettyHttpExchangeDelegate(jaxWsContext,req,resp);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#hashCode()
     */
    @Override
    public int hashCode()
    {
        return _delegate.hashCode();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getRequestHeaders()
     */
    @Override
    public Headers getRequestHeaders()
    {
        return _delegate.getRequestHeaders();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getResponseHeaders()
     */
    @Override
    public Headers getResponseHeaders()
    {
        return _delegate.getResponseHeaders();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getRequestURI()
     */
    @Override
    public URI getRequestURI()
    {
        return _delegate.getRequestURI();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getRequestMethod()
     */
    @Override
    public String getRequestMethod()
    {
        return _delegate.getRequestMethod();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getHttpContext()
     */
    @Override
    public HttpContext getHttpContext()
    {
        return _delegate.getHttpContext();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#close()
     */
    @Override
    public void close()
    {
        _delegate.close();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj)
    {
        return _delegate.equals(obj);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getRequestBody()
     */
    @Override
    public InputStream getRequestBody()
    {
        return _delegate.getRequestBody();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getResponseBody()
     */
    @Override
    public OutputStream getResponseBody()
    {
        return _delegate.getResponseBody();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#sendResponseHeaders(int, long)
     */
    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException
    {
        _delegate.sendResponseHeaders(rCode,responseLength);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getRemoteAddress()
     */
    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return _delegate.getRemoteAddress();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getResponseCode()
     */
    @Override
    public int getResponseCode()
    {
        return _delegate.getResponseCode();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getLocalAddress()
     */
    @Override
    public InetSocketAddress getLocalAddress()
    {
        return _delegate.getLocalAddress();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getProtocol()
     */
    @Override
    public String getProtocol()
    {
        return _delegate.getProtocol();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        return _delegate.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        _delegate.setAttribute(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#setStreams(java.io.InputStream, java.io.OutputStream)
     */
    @Override
    public void setStreams(InputStream i, OutputStream o)
    {
        _delegate.setStreams(i,o);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#getPrincipal()
     */
    @Override
    public HttpPrincipal getPrincipal()
    {
        return _delegate.getPrincipal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#setPrincipal(com.sun.net.httpserver.HttpPrincipal)
     */
    public void setPrincipal(HttpPrincipal principal)
    {
        _delegate.setPrincipal(principal);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.spi.JettyExchange#toString()
     */
    @Override
    public String toString()
    {
        return _delegate.toString();
    }

}
