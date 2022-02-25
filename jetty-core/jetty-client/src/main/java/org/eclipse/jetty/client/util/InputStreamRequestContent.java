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

package org.eclipse.jetty.client.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;

/**
 * <p>A {@link Request.Content} that produces content from an {@link InputStream}.</p>
 * <p>The input stream is read once and therefore fully consumed.</p>
 * <p>It is possible to specify, at the constructor, a buffer size used to read
 * content from the stream, by default 1024 bytes.</p>
 * <p>The {@link InputStream} passed to the constructor is by default closed
 * when is it fully consumed.</p>
 */
public class InputStreamRequestContent extends AbstractRequestContent
{
    private static final int DEFAULT_BUFFER_SIZE = 4096;

    private final InputStream stream;
    private final int bufferSize;
    private Subscription subscription;

    public InputStreamRequestContent(InputStream stream)
    {
        this(stream, DEFAULT_BUFFER_SIZE);
    }

    public InputStreamRequestContent(String contentType, InputStream stream)
    {
        this(contentType, stream, DEFAULT_BUFFER_SIZE);
    }

    public InputStreamRequestContent(InputStream stream, int bufferSize)
    {
        this("application/octet-stream", stream, bufferSize);
    }

    public InputStreamRequestContent(String contentType, InputStream stream, int bufferSize)
    {
        super(contentType);
        this.stream = stream;
        this.bufferSize = bufferSize;
    }

    @Override
    protected Subscription newSubscription(Consumer consumer, boolean emitInitialContent)
    {
        if (subscription != null)
            throw new IllegalStateException("Multiple subscriptions not supported on " + this);
        return subscription = new SubscriptionImpl(consumer, emitInitialContent);
    }

    @Override
    public void fail(Throwable failure)
    {
        super.fail(failure);
        close();
    }

    protected ByteBuffer onRead(byte[] buffer, int offset, int length)
    {
        return ByteBuffer.wrap(buffer, offset, length);
    }

    protected void onReadFailure(Throwable failure)
    {
    }

    private void close()
    {
        IO.close(stream);
    }

    private class SubscriptionImpl extends AbstractSubscription
    {
        private boolean terminated;

        private SubscriptionImpl(Consumer consumer, boolean emitInitialContent)
        {
            super(consumer, emitInitialContent);
        }

        @Override
        protected boolean produceContent(Producer producer) throws IOException
        {
            if (terminated)
                throw new EOFException("Demand after last content");
            byte[] bytes = new byte[bufferSize];
            int read = read(bytes);
            ByteBuffer buffer = BufferUtil.EMPTY_BUFFER;
            boolean last = true;
            if (read < 0)
            {
                close();
                terminated = true;
            }
            else
            {
                buffer = onRead(bytes, 0, read);
                last = false;
            }
            return producer.produce(buffer, last, Callback.NOOP);
        }

        private int read(byte[] bytes) throws IOException
        {
            try
            {
                return stream.read(bytes);
            }
            catch (Throwable x)
            {
                onReadFailure(x);
                throw x;
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            super.fail(failure);
            close();
        }
    }
}
