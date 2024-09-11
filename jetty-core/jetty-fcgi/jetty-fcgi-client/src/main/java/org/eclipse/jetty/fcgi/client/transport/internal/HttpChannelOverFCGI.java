//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.client.transport.internal;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.transport.HttpChannel;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.client.transport.HttpSender;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

public class HttpChannelOverFCGI extends HttpChannel
{
    private final HttpConnectionOverFCGI connection;
    private final HttpSenderOverFCGI sender;
    private final HttpReceiverOverFCGI receiver;
    private int request;
    private HttpVersion version;

    public HttpChannelOverFCGI(HttpConnectionOverFCGI connection)
    {
        super(connection.getHttpDestination());
        this.connection = connection;
        this.sender = new HttpSenderOverFCGI(this);
        this.receiver = new HttpReceiverOverFCGI(this);
    }

    public HttpConnectionOverFCGI getHttpConnection()
    {
        return connection;
    }

    protected int getRequest()
    {
        return request;
    }

    void setRequest(int request)
    {
        this.request = request;
    }

    @Override
    protected Connection getConnection()
    {
        return connection;
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
    public void send(HttpExchange exchange)
    {
        version = exchange.getRequest().getVersion();
        sender.send(exchange);
    }

    @Override
    public void release()
    {
        connection.release();
    }

    protected void responseBegin(int code, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;
        exchange.getResponse().version(version).status(code).reason(reason);
        receiver.responseBegin(exchange);
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

    protected void content(Content.Chunk chunk)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.content(chunk);
    }

    protected void responseContentAvailable()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseContentAvailable(exchange);
    }

    protected void end()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.end();
    }

    protected void responseSuccess()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseSuccess(exchange);
    }

    protected void responseFailure(Throwable failure, Promise<Boolean> promise)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            receiver.responseFailure(failure, promise);
        else
            promise.succeeded(false);
    }

    void eof()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            connection.close();
    }

    @Override
    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        super.exchangeTerminated(exchange, result);
        HttpFields responseHeaders = result.getResponse().getHeaders();
        if (result.isFailed())
            connection.close(result.getFailure());
        else if (connection.isShutdown() || connection.isCloseByHTTP(responseHeaders))
            connection.close();
        else
            release();
    }

    protected void flush(ByteBufferPool.Accumulator accumulator, Callback callback)
    {
        connection.getFlusher().flush(accumulator, callback);
    }

    void receive()
    {
        receiver.receive();
    }
}
