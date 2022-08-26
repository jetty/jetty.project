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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility implementation of FrameHandler that de-fragments text frames & binary frames into a whole messages before
 * calling {@link #onText(String, Callback)} or {@link #onBinary(ByteBuffer, Callback)}.
 * This is a demanding frame handler so
 * Flow control is by default automatic, but an implementation
 * may extend {@link #isDemanding()} to return true and then explicityly control
 * demand with calls to {@link CoreSession#demand(long)}
 */
public abstract class AbstractMessageHandler implements FrameHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractMessageHandler.class);

    private CoreSession _coreSession;
    private byte dataType = OpCode.UNDEFINED;

    public CoreSession getCoreSession()
    {
        return _coreSession;
    }

    /**
     * Method called when a complete text message is received.
     *
     * @param message  the received text payload
     * @param callback The callback to signal completion of handling.
     */
    protected void onText(String message, Callback callback)
    {
        callback.failed(new BadPayloadException("Text Not Accepted"));
    }

    /**
     * Method called when a complete binary message is received.
     *
     * @param message  The binary payload
     * @param callback The callback to signal completion of handling.
     */
    protected void onBinary(ByteBuffer message, Callback callback)
    {
        callback.failed(new BadPayloadException("Binary Not Accepted"));
    }

    protected void onPingFrame(Frame frame, Callback callback)
    {
        _coreSession.sendFrame(new Frame(OpCode.PONG, true, frame.getPayload()), callback, false);
    }

    protected void onPongFrame(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    protected void onCloseFrame(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    /**
     * Send a String as a single text frame.
     *
     * @param message  The message to send
     * @param callback The callback to call when the send is complete
     * @param batch    The batch mode to send the frames in.
     */
    public void sendText(String message, Callback callback, boolean batch)
    {
        getCoreSession().sendFrame(new Frame(OpCode.TEXT, true, message), callback, batch);
    }

    /**
     * Send a ByteBuffer as a single binary frame.
     *
     * @param message  The message to send
     * @param callback The callback to call when the send is complete
     * @param batch    The batch mode to send the frames in.
     */
    public void sendBinary(ByteBuffer message, Callback callback, boolean batch)
    {
        getCoreSession().sendFrame(new Frame(OpCode.BINARY, true, message), callback, batch);
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", coreSession);

        this._coreSession = coreSession;
        callback.succeeded();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFrame {}", frame);

        switch (frame.getOpCode())
        {
            case OpCode.CLOSE:
                onCloseFrame(frame, callback);
                break;
            case OpCode.PING:
                onPingFrame(frame, callback);
                break;
            case OpCode.PONG:
                onPongFrame(frame, callback);
                break;
            case OpCode.TEXT:
                dataType = OpCode.TEXT;
                onTextFrame(frame, callback);
                break;
            case OpCode.BINARY:
                dataType = OpCode.BINARY;
                onBinaryFrame(frame, callback);
                break;
            case OpCode.CONTINUATION:
                onContinuationFrame(frame, callback);
                if (frame.isFin())
                    dataType = OpCode.UNDEFINED;
                break;
            default:
                callback.failed(new IllegalStateException());
        }
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onError ", cause);
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClosed {}", closeStatus);
        callback.succeeded();
    }

    protected abstract void onTextFrame(Frame frame, Callback callback);

    protected abstract void onBinaryFrame(Frame frame, Callback callback);

    private void onContinuationFrame(Frame frame, Callback callback)
    {
        switch (dataType)
        {
            case OpCode.BINARY:
                onBinaryFrame(frame, callback);
                break;

            case OpCode.TEXT:
                onTextFrame(frame, callback);
                break;

            default:
                throw new IllegalStateException();
        }
    }
}
