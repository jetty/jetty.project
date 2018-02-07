//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

/**
 * Simply breaks up the FrameHandler.onFrame() into specific frame type methods
 */
public abstract class AbstractFrameTypeHandler implements FrameHandler
{
    protected Channel channel;
    protected Throwable errorCause;
    protected CloseStatus closeStatus;

    @Override
    public void onOpen(Channel channel) throws Exception
    {
        this.channel = channel;
    }

    @Override
    public void onFrame(Frame frame, Callback callback) throws Exception
    {
        switch (frame.getOpCode())
        {
            case OpCode.TEXT:
                onText(frame, callback);
                break;
            case OpCode.BINARY:
                onBinary(frame, callback);
                break;
            case OpCode.CONTINUATION:
                onContinuation(frame, callback);
                break;
            case OpCode.PING:
                onPing(frame, callback);
                break;
            case OpCode.PONG:
                onPong(frame, callback);
                break;
            case OpCode.CLOSE:
                onClose(frame, callback);
                break;
        }
    }

    public abstract void onText(Frame frame, Callback callback);

    public abstract void onBinary(Frame frame, Callback callback);

    public abstract void onContinuation(Frame frame, Callback callback);

    public void onPing(Frame frame, Callback callback)
    {
        channel.sendFrame(new PongFrame().setPayload(copyOf(frame.getPayload())), callback, BatchMode.OFF);
    }

    public void onPong(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    public void onClose(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus) throws Exception
    {
        this.closeStatus = closeStatus;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        this.errorCause = cause;
    }

    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    @SuppressWarnings("Duplicates")
    public ByteBuffer copyOf(ByteBuffer payload)
    {
        if (payload == null)
            return null;

        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload.slice());
        copy.flip();
        return copy;
    }
}
