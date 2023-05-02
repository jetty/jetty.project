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

package org.eclipse.jetty.websocket.core.messages;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * <p>A {@link MessageSink} implementation that delivers BINARY frames
 * to the application function passed to the constructor in the form
 * of a {@link ByteBuffer}.</p>
 */
public class PartialByteBufferMessageSink extends AbstractMessageSink
{
    /**
     * Creates a new {@link PartialByteBufferMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHandle the application function to invoke when a new frame has arrived
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public PartialByteBufferMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        super(session, methodHandle, autoDemand);
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (frame.hasPayload() || frame.isFin())
            {
                invoke(getMethodHandle(), frame.getPayload(), frame.isFin(), callback);
                autoDemand();
            }
            else
            {
                callback.succeeded();
                getCoreSession().demand(1);
            }
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    protected void invoke(MethodHandle methodHandle, ByteBuffer byteBuffer, boolean fin, Callback callback) throws Throwable
    {
        methodHandle.invoke(byteBuffer, fin);
        callback.succeeded();
    }
}
