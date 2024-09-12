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

package org.eclipse.jetty.compression.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressionResponse extends Response.Wrapper implements Callback, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(CompressionResponse.class);

    private final Callback callback;
    private final CompressionConfig config;
    private final Compression compression;
    private final EncoderSink encoderSink;
    private boolean last;

    public CompressionResponse(Compression compression, Request request, Response wrapped, Callback callback, CompressionConfig config)
    {
        super(request, wrapped);
        this.callback = callback;
        this.config = config;
        this.compression = compression;
        this.encoderSink = compression.newEncoderSink(wrapped);
        getHeaders().put(compression.getContentEncodingField());
    }

    @Override
    public void failed(Throwable x)
    {
        this.callback.failed(x);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return this.callback.getInvocationType();
    }

    @Override
    public void succeeded()
    {
        // We need to write nothing here to intercept the committing of the
        // response and possibly change headers in case write is never called.
        if (last)
            this.callback.succeeded();
        else
            write(true, null, this.callback);
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        // TODO: on response commit, determine if response Content-Type is an allowed
        // to be compressed type based on the CompressionConfig.mimeType settings.
        // We need to modify the response headers before commit to know if we
        // allow compression (or not).

        encoderSink.write(last, content, callback);
        if (last)
            this.last = true;
    }
}
