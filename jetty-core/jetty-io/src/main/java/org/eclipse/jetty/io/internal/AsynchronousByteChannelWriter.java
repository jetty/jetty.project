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

package org.eclipse.jetty.io.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.util.Objects;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class AsynchronousByteChannelWriter extends IteratingCallback implements CompletionHandler<Integer, ByteBuffer>
{
    private final AsynchronousByteChannel channel;
    private final boolean last;
    private final RetainableByteBuffer buffer;
    private final Callback callback;

    public AsynchronousByteChannelWriter(AsynchronousByteChannel channel, boolean last, RetainableByteBuffer buffer, Callback callback)
    {
        this.channel = Objects.requireNonNull(channel);
        this.last = last;
        this.buffer = Objects.requireNonNull(buffer);
        this.callback = Objects.requireNonNull(callback);
        buffer.retain();
    }

    @Override
    protected Action process() throws IOException
    {
        if (!buffer.hasRemaining())
            return Action.SUCCEEDED;

        buffer.read(input ->
        {
            // The write operation is asynchronous: do not modify the input ByteBuffer,
            // and consume the RetainableByteBuffer only when bytes have been written.
            ByteBuffer slice = input.slice();
            channel.write(slice, slice, this);
            return 0;
        });
        return Action.SCHEDULED;
    }

    @Override
    public void completed(Integer written, ByteBuffer slice)
    {
        try
        {
            buffer.consume(written);
            if (slice.hasRemaining())
                channel.write(slice, slice, this);
            else
                succeeded();
        }
        catch (Throwable x)
        {
            failed(x);
        }
    }

    @Override
    public void failed(Throwable x, ByteBuffer attachment)
    {
        failed(x);
    }

    @Override
    protected void onCompleted(Throwable causeOrNull)
    {
        buffer.release();
        if (causeOrNull != null || last)
            IO.close(channel);
        super.onCompleted(causeOrNull);
    }

    @Override
    protected void onCompleteSuccess()
    {
        callback.succeeded();
    }

    @Override
    protected void onCompleteFailure(Throwable cause)
    {
        callback.failed(cause);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return callback.getInvocationType();
    }
}
