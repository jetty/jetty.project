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

package org.eclipse.jetty.fcgi.client.http;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.IdleTimeout;

public class HttpChannelOverFCGI extends HttpChannel
{
    private final HttpConnectionOverFCGI connection;
    private final Flusher flusher;
    private final int request;
    private final HttpSenderOverFCGI sender;
    private final HttpReceiverOverFCGI receiver;
    private final FCGIIdleTimeout idle;
    private HttpVersion version;

    public HttpChannelOverFCGI(final HttpConnectionOverFCGI connection, Flusher flusher, int request, long idleTimeout)
    {
        super(connection.getHttpDestination());
        this.connection = connection;
        this.flusher = flusher;
        this.request = request;
        this.sender = new HttpSenderOverFCGI(this);
        this.receiver = new HttpReceiverOverFCGI(this);
        this.idle = new FCGIIdleTimeout(connection, idleTimeout);
    }

    protected int getRequest()
    {
        return request;
    }

    @Override
    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            version = exchange.getRequest().getVersion();
            sender.send(exchange);
        }
    }

    @Override
    public void proceed(HttpExchange exchange, Throwable failure)
    {
        sender.proceed(exchange, failure);
    }

    @Override
    public boolean abort(Throwable cause)
    {
        sender.abort(cause);
        return receiver.abort(cause);
    }

    protected void responseBegin(int code, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            exchange.getResponse().version(version).status(code).reason(reason);
            receiver.responseBegin(exchange);
        }
    }

    protected void responseHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseHeader(exchange, field);
    }

    protected void responseHeaders()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseHeaders(exchange);
    }

    protected void content(ByteBuffer buffer)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseContent(exchange, buffer);
    }

    protected void responseSuccess()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseSuccess(exchange);
    }

    @Override
    public void exchangeTerminated(Result result)
    {
        super.exchangeTerminated(result);
        idle.onClose();
        boolean close = result.isFailed();
        HttpFields responseHeaders = result.getResponse().getHeaders();
        close |= responseHeaders.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
        if (close)
            connection.close();
        else
            connection.release();
    }

    protected void flush(Generator.Result... results)
    {
        flusher.flush(results);
    }

    private class FCGIIdleTimeout extends IdleTimeout
    {
        private final HttpConnectionOverFCGI connection;

        public FCGIIdleTimeout(HttpConnectionOverFCGI connection, long idleTimeout)
        {
            super(connection.getHttpDestination().getHttpClient().getScheduler());
            this.connection = connection;
            setIdleTimeout(idleTimeout);
        }

        @Override
        protected void onIdleExpired(TimeoutException timeout)
        {
            LOG.debug("Idle timeout for request {}", request);
            abort(timeout);
        }

        @Override
        public boolean isOpen()
        {
            return connection.getEndPoint().isOpen();
        }
    }
}
