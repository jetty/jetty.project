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
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferCallbackAccumulator;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;

/**
 * <p>A {@link MessageSink} implementation that accumulates BINARY frames
 * into a message that is then delivered to the application function
 * passed to the constructor in the form of a {@code byte[]}.</p>
 */
public class ByteArrayMessageSink extends AbstractMessageSink
{
    private ByteBufferCallbackAccumulator accumulator;

    /**
     * Creates a new {@link ByteArrayMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHandle the application function to invoke when a new message has been assembled
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public ByteArrayMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        super(session, methodHandle, autoDemand);

        // This uses the offset length byte array signature not supported by jakarta websocket.
        // The jakarta layer instead uses decoders for whole byte array messages instead of this message sink.
        MethodType onMessageType = MethodType.methodType(Void.TYPE, byte[].class, int.class, int.class);
        if (methodHandle.type().changeReturnType(void.class) != onMessageType.changeReturnType(void.class))
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            long size = (accumulator == null ? 0 : accumulator.getLength()) + frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                callback.failed(new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize)));
                return;
            }

            ByteBuffer payload = frame.getPayload();
            if (frame.isFin() && accumulator == null)
            {
                byte[] buf = BufferUtil.toArray(payload);
                getMethodHandle().invoke(buf, 0, buf.length);
                callback.succeeded();
                autoDemand();
                return;
            }

            if (!frame.isFin() && !frame.hasPayload())
            {
                callback.succeeded();
                getCoreSession().demand(1);
                return;
            }

            if (accumulator == null)
                accumulator = new ByteBufferCallbackAccumulator();
            accumulator.addEntry(payload, callback);

            if (frame.isFin())
            {
                // Do not complete twice the callback if the invocation fails.
                callback = Callback.NOOP;
                byte[] buf = accumulator.takeByteArray();
                getMethodHandle().invoke(buf, 0, buf.length);
                autoDemand();
            }
            else
            {
                getCoreSession().demand(1);
            }

        }
        catch (Throwable t)
        {
            if (accumulator != null)
                accumulator.fail(t);
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
                accumulator = null;
        }
    }
}
