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

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

public class ByteBufferMessageSink extends org.eclipse.jetty.websocket.core.messages.ByteBufferMessageSink
{
    public ByteBufferMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
    }

    @Override
    protected void invoke(MethodHolder methodHolder, RetainableByteBuffer buffer, org.eclipse.jetty.util.Callback callback) throws Throwable
    {
        // The buffer is coalesced, so buffer.read() is a single invocation.
        // Differently from core WebSocket, we pass the callback to the
        // application, that may use the ByteBuffer until callback completion.
        buffer.retain();
        buffer.read(b ->
        {
            int r = b.remaining();
            var cb = org.eclipse.jetty.util.Callback.from(() ->
            {
                buffer.consume(r);
                buffer.release();
            }, callback);
            try
            {
                methodHolder.invoke(b.slice(), Callback.from(cb::succeeded, cb::failed));
                return 0;
            }
            catch (Throwable x)
            {
                buffer.release();
                throw x;
            }
        });
    }
}
