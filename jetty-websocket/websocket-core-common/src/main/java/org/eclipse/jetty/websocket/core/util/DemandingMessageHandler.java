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

package org.eclipse.jetty.websocket.core.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferCallbackAccumulator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;

/**
 * <p>
 * A utility implementation of FrameHandler that de-fragments text frames and binary frames into a whole messages before
 * calling {@link #onText(String, Callback)} or {@link #onBinary(ByteBuffer, Callback)}.
 * </p>
 * <p>
 * This class does not implicitly demand more frames when the Callback passed to either onText() or onBinary() is succeeded.
 * Implementations of this must always call {@link #demand()} to receive the next WebSocket frame.
 * This is done by this class for non-final data frames, but the implementations of
 * {@link #onText(String, Callback)} and {@link #onBinary(ByteBuffer, Callback)} must call {@link #demand()} manually.
 * </p>
 * <p>
 * Because this is a demanding FrameHandler it can be more efficient for binary messages by avoiding copies
 * for aggregation until the full message has been received.
 * </p>
 * @see AutoDemandingMessageHandler
 */
public class DemandingMessageHandler extends AbstractMessageHandler
{
    private Utf8StringBuilder _textMessageBuffer;
    private ByteBufferCallbackAccumulator _binaryMessageBuffer;

    public void demand()
    {
        getCoreSession().demand(1);
    }

    @Override
    protected void onPingFrame(Frame frame, Callback callback)
    {
        super.onPingFrame(frame, callback);
        demand();
    }

    @Override
    protected void onPongFrame(Frame frame, Callback callback)
    {
        super.onPongFrame(frame, callback);
        demand();
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        super.onOpen(coreSession, callback);
        demand();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        if (_textMessageBuffer != null)
        {
            _textMessageBuffer.reset();
            _textMessageBuffer = null;
        }

        if (_binaryMessageBuffer != null)
        {
            _binaryMessageBuffer.close();
            _binaryMessageBuffer = null;
        }

        super.onClosed(closeStatus, callback);
    }

    @Override
    protected void onTextFrame(Frame frame, Callback callback)
    {
        try
        {
            if (_textMessageBuffer == null)
                _textMessageBuffer = new Utf8StringBuilder();

            if (frame.hasPayload())
            {
                long size = _textMessageBuffer.length() + frame.getPayloadLength();
                long maxTextMessageSize = getCoreSession().getMaxTextMessageSize();
                if (maxTextMessageSize > 0 && size > maxTextMessageSize)
                {
                    throw new MessageTooLargeException(String.format("Text message too large: (actual) %,d > (configured max text message size) %,d",
                        size, maxTextMessageSize));
                }

                _textMessageBuffer.append(frame.getPayload());
            }

            if (frame.isFin())
            {
                onText(_textMessageBuffer.toString(), callback);
                _textMessageBuffer.reset();
            }
            else
            {
                callback.succeeded();
                demand();
            }
        }
        catch (Utf8Appendable.NotUtf8Exception e)
        {
            if (_textMessageBuffer != null)
            {
                _textMessageBuffer.reset();
                _textMessageBuffer = null;
            }
            callback.failed(new BadPayloadException(e));
        }
        catch (Throwable t)
        {
            if (_textMessageBuffer != null)
            {
                _textMessageBuffer.reset();
                _textMessageBuffer = null;
            }
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
                _textMessageBuffer = null;
        }
    }

    @Override
    protected void onBinaryFrame(Frame frame, Callback callback)
    {
        try
        {
            long size = (_binaryMessageBuffer == null ? 0 : _binaryMessageBuffer.getLength()) + frame.getPayloadLength();
            long maxBinaryMessageSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxBinaryMessageSize > 0 && size > maxBinaryMessageSize)
            {
                throw new MessageTooLargeException(String.format("Binary message too large: (actual) %,d > (configured max binary message size) %,d",
                    size, maxBinaryMessageSize));
            }

            // If we are fin and no OutputStream has been created we don't need to aggregate.
            if (frame.isFin() && (_binaryMessageBuffer == null))
            {
                if (frame.hasPayload())
                    onBinary(frame.getPayload(), callback);
                else
                    onBinary(BufferUtil.EMPTY_BUFFER, callback);
                return;
            }

            // Aggregate the frame payload.
            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                if (_binaryMessageBuffer == null)
                    _binaryMessageBuffer = new ByteBufferCallbackAccumulator();
                _binaryMessageBuffer.addEntry(payload, callback);
                callback = Callback.NOOP;
            }

            // If the onBinary method throws we don't want to fail callback twice.
            if (frame.isFin())
            {
                ByteBufferPool bufferPool = getCoreSession().getByteBufferPool();
                ByteBuffer buffer = bufferPool.acquire(_binaryMessageBuffer.getLength(), false);
                _binaryMessageBuffer.writeTo(buffer);
                onBinary(buffer, Callback.from(() -> bufferPool.release(buffer)));
            }
            else
            {
                callback.succeeded();
                demand();
            }
        }
        catch (Throwable t)
        {
            if (_binaryMessageBuffer != null)
            {
                _binaryMessageBuffer.fail(t);
                _binaryMessageBuffer = null;
            }
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
                _binaryMessageBuffer = null;
        }
    }

    @Override
    public boolean isDemanding()
    {
        return true;
    }
}
