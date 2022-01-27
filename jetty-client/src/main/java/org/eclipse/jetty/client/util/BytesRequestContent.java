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
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * A {@link Request.Content} for byte arrays.
 */
public class BytesRequestContent extends AbstractRequestContent
{
    private final byte[][] bytes;
    private final long length;

    public BytesRequestContent(byte[]... bytes)
    {
        this("application/octet-stream", bytes);
    }

    public BytesRequestContent(String contentType, byte[]... bytes)
    {
        super(contentType);
        this.bytes = bytes;
        this.length = Arrays.stream(bytes).mapToLong(a -> a.length).sum();
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public boolean isReproducible()
    {
        return true;
    }

    @Override
    protected Subscription newSubscription(Consumer consumer, boolean emitInitialContent)
    {
        return new SubscriptionImpl(consumer, emitInitialContent);
    }

    private class SubscriptionImpl extends AbstractSubscription
    {
        private int index;

        private SubscriptionImpl(Consumer consumer, boolean emitInitialContent)
        {
            super(consumer, emitInitialContent);
        }

        @Override
        protected boolean produceContent(Producer producer) throws IOException
        {
            if (index < 0)
                throw new EOFException("Demand after last content");
            ByteBuffer buffer = BufferUtil.EMPTY_BUFFER;
            if (index < bytes.length)
                buffer = ByteBuffer.wrap(bytes[index++]);
            boolean lastContent = index == bytes.length;
            if (lastContent)
                index = -1;
            return producer.produce(buffer, lastContent, Callback.NOOP);
        }
    }
}
