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

package org.eclipse.jetty.fcgi.client.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.Callback;

public class HttpReceiverOverFCGI extends HttpReceiver
{
    public HttpReceiverOverFCGI(HttpChannel channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverFCGI getHttpChannel()
    {
        return (HttpChannelOverFCGI)super.getHttpChannel();
    }

    @Override
    protected boolean responseBegin(HttpExchange exchange)
    {
        return super.responseBegin(exchange);
    }

    @Override
    protected boolean responseHeader(HttpExchange exchange, HttpField field)
    {
        return super.responseHeader(exchange, field);
    }

    @Override
    protected boolean responseHeaders(HttpExchange exchange)
    {
        return super.responseHeaders(exchange);
    }

    @Override
    protected boolean responseContent(HttpExchange exchange, ByteBuffer buffer, Callback callback)
    {
        return super.responseContent(exchange, buffer, callback);
    }

    @Override
    protected boolean responseSuccess(HttpExchange exchange)
    {
        return super.responseSuccess(exchange);
    }

    @Override
    protected boolean responseFailure(Throwable failure)
    {
        return super.responseFailure(failure);
    }

    @Override
    protected void receive()
    {
        getHttpChannel().receive();
    }
}
