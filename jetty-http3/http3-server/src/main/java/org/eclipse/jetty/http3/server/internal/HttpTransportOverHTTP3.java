//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpTransportOverHTTP3 implements HttpTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpTransportOverHTTP3.class);

    private final HTTP3Stream stream;

    public HttpTransportOverHTTP3(HTTP3Stream stream)
    {
        this.stream = stream;
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        CompletableFuture<Stream> future = stream.respond(new HeadersFrame(response, true));
        future.whenComplete((s, x) ->
        {
            if (x == null)
                callback.succeeded();
            else
                callback.failed(x);
        });
    }

    @Override
    public boolean isPushSupported()
    {
        return false;
    }

    @Override
    public void push(MetaData.Request request)
    {

    }

    @Override
    public void onCompleted()
    {

    }

    @Override
    public void abort(Throwable failure)
    {

    }
}
