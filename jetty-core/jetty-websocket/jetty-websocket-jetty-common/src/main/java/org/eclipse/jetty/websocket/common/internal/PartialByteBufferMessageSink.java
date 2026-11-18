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

package org.eclipse.jetty.websocket.common.internal;

import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

public class PartialByteBufferMessageSink extends org.eclipse.jetty.websocket.core.messages.PartialByteBufferMessageSink
{
    public PartialByteBufferMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
    }

    @Override
    protected void invoke(MethodHolder methodHolder, RetainableByteBuffer buffer, boolean fin, org.eclipse.jetty.util.Callback callback) throws Throwable
    {
        if (!buffer.hasRemaining())
        {
            methodHolder.invoke(BufferUtil.EMPTY_BUFFER, fin, Callback.from(callback::succeeded, callback::failed));
            return;
        }

        // The buffer may wrap multiple ByteBuffers.
        // Differently from core WebSocket, we pass the callback to the
        // application, that may use the ByteBuffer until callback completion.
        new ByteBufferAsyncInvoker(methodHolder, buffer, fin, callback).iterate();
    }

    /// Invokes the application function with the (possibly multiple)
    /// [ByteBuffer]s wrapped by a [RetainableByteBuffer], one [ByteBuffer] at a time.
    ///
    /// The invocation is asynchronous, and the [ByteBuffer] is valid until the
    /// corresponding [Callback] is completed.
    ///
    /// When the [Callback] is completed the [ByteBuffer] can be recycled, and
    /// the application function is invoked with the next [ByteBuffer], if any.
    private static class ByteBufferAsyncInvoker extends IteratingCallback
    {
        private final Callback apiCallback = Callback.from(this::succeeded, this::failed);
        private final MethodHolder methodHolder;
        private final RetainableByteBuffer buffer;
        private final boolean fin;
        private final org.eclipse.jetty.util.Callback callback;
        private int length;

        private ByteBufferAsyncInvoker(MethodHolder methodHolder, RetainableByteBuffer buffer, boolean fin, org.eclipse.jetty.util.Callback callback)
        {
            this.methodHolder = Objects.requireNonNull(methodHolder);
            this.buffer = Objects.requireNonNull(buffer);
            this.fin = fin;
            this.callback = Objects.requireNonNull(callback);
            buffer.retain();
        }

        @Override
        protected Action process() throws Throwable
        {
            // Deliver at least once, even if there are no bytes.
            if (!buffer.hasRemaining())
                return Action.SUCCEEDED;

            long remaining = buffer.remaining();
            buffer.read(b ->
            {
                // The application may use the ByteBuffer asynchronously:
                // do not modify it, and consume the RetainableByteBuffer
                // only when the application completes the callback.
                ByteBuffer slice = b.slice();
                length = slice.remaining();
                methodHolder.invoke(slice, fin && length == remaining, apiCallback);
                return 0;
            });
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            buffer.consume(length);
            length = 0;
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            buffer.release();
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
}
