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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressionResponse extends Response.Wrapper implements Callback, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(CompressionResponse.class);

    enum State
    {
        MIGHT_COMPRESS,
        NOT_COMPRESSING,
        COMPRESSING
    }

    private final Callback callback;
    private final CompressionConfig config;
    private final Compression compression;
    private EncoderSink encoderSink;
    private AtomicReference<State> state = new AtomicReference<>(State.MIGHT_COMPRESS);
    private boolean last;

    public CompressionResponse(Compression compression, Request request, Response wrapped, Callback callback, CompressionConfig config)
    {
        super(request, wrapped);
        this.callback = callback;
        this.config = config;
        this.compression = compression;
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
        switch (state.get())
        {
            case MIGHT_COMPRESS ->
            {
                boolean compressing = false;

                HttpField contentTypeField = getHeaders().getField(HttpHeader.CONTENT_TYPE);
                if (contentTypeField == null)
                {
                    compressing = state.compareAndSet(State.MIGHT_COMPRESS, State.COMPRESSING);
                }
                else
                {
                    String mimeType = MimeTypes.getContentTypeWithoutCharset(contentTypeField.getValue());
                    if (config.isCompressMimeTypeSupported(mimeType))
                    {
                        compressing = state.compareAndSet(State.MIGHT_COMPRESS, State.COMPRESSING);
                    }
                    else
                    {
                        state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                    }
                }

                if (compressing)
                {
                    this.encoderSink = compression.newEncoderSink(getWrapped());
                    getHeaders().put(compression.getContentEncodingField());
                }

                this.write(last, content, callback);
            }
            case COMPRESSING ->
            {
                encoderSink.write(last, content, callback);
                if (last)
                    this.last = true;
            }
            case NOT_COMPRESSING ->
            {
                super.write(last, content, callback);
            }
        }
    }
}
