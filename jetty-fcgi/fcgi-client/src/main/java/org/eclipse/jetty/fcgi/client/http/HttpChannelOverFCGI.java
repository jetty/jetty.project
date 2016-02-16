//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.util.Callback;

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
    protected HttpSender getHttpSender()
    {
        return sender;
    }

    @Override
    protected HttpReceiver getHttpReceiver()
    {
        return receiver;
    }

    @Override
    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            version = exchange.getRequest().getVersion();
            idle.onOpen();
            sender.send(exchange);
        }
    }

    @Override
    public void release()
    {
        connection.release(this);
    }

    protected boolean responseBegin(int code, String reason)
    {
        idle.notIdle();
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;
        exchange.getResponse().version(version).status(code).reason(reason);
        return receiver.responseBegin(exchange);
    }

    protected boolean responseHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        return exchange != null && receiver.responseHeader(exchange, field);
    }

    protected boolean responseHeaders()
    {
       idle.notIdle();
        HttpExchange exchange = getHttpExchange();
        return exchange != null && receiver.responseHeaders(exchange);
    }

    protected boolean content(ByteBuffer buffer, Callback callback)
    {
        idle.notIdle();
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            return receiver.responseContent(exchange, buffer, callback);
        callback.succeeded();
        return false;
    }

    protected boolean responseSuccess()
    {
        HttpExchange exchange = getHttpExchange();
        return exchange != null && receiver.responseSuccess(exchange);
    }

    protected boolean responseFailure(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        return exchange != null && receiver.responseFailure(failure);
    }

    @Override
    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        super.exchangeTerminated(exchange, result);
        idle.onClose();
        HttpFields responseHeaders = result.getResponse().getHeaders();
        if (result.isFailed())
            connection.close(result.getFailure());
        else if (!connection.closeByHTTP(responseHeaders))
            release();
    }

    protected void flush(Generator.Result... results)
    {
        flusher.flush(results);
    }

    private class FCGIIdleTimeout extends IdleTimeout
    {
        private final HttpConnectionOverFCGI connection;
        private boolean open;

        public FCGIIdleTimeout(HttpConnectionOverFCGI connection, long idleTimeout)
        {
            super(connection.getHttpDestination().getHttpClient().getScheduler());
            this.connection = connection;
            setIdleTimeout(idleTimeout);
        }

        @Override
        public void onOpen()
        {
            open = true;
            notIdle();
            super.onOpen();
        }

        @Override
        public void onClose()
        {
            super.onClose();
            open = false;
        }

        @Override
        protected void onIdleExpired(TimeoutException timeout)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Idle timeout for request {}", request);
            connection.abort(timeout);
        }

        @Override
        public boolean isOpen()
        {
            return open;
        }
    }
}
