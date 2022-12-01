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

import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;

/**
 * <p>
 * A utility implementation of FrameHandler that de-fragments text frames and binary frames into a whole messages before
 * calling {@link #onText(String, Callback)} or {@link #onBinary(ByteBuffer, Callback)}.
 * </p>
 * <p>
 * This class implicitly demands more frames when the Callback passed to either onText() or onBinary() is succeeded.
 * It is preferable to use {@link DemandingMessageHandler} as the aggregation for binary messages can be done more
 * efficiently if the frame handler can manage its own demand.
 * </p>
 * @see DemandingMessageHandler
 */
public class AutoDemandingMessageHandler extends AbstractMessageHandler
{
    private Utf8StringBuilder _textMessageBuffer;
    private ByteBufferAccumulator _binaryMessageBuffer;
    private long _binaryLength = 0;

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
            _binaryMessageBuffer.reset();
            _binaryMessageBuffer = null;
            _binaryLength = 0;
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
            long size = _binaryLength + frame.getPayloadLength();
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
                if (_binaryMessageBuffer == null)
                    _binaryMessageBuffer = new ByteBufferAccumulator();
                _binaryLength += frame.getPayloadLength();
                _binaryMessageBuffer.copyBuffer(frame.getPayload());
            }

            if (frame.isFin())
            {
                ByteBuffer buffer = _binaryMessageBuffer.takeByteBuffer();
                ByteBufferAccumulator accumulator = _binaryMessageBuffer;
                onBinary(buffer, Callback.from(callback, () -> accumulator.getByteBufferPool().release(buffer)));
                _binaryMessageBuffer.reset();
                _binaryMessageBuffer = null;
                _binaryLength = 0;
            }
            else
            {
                callback.succeeded();
            }
        }
        catch (Throwable t)
        {
            if (_binaryMessageBuffer != null)
            {
                _binaryMessageBuffer.reset();
                _binaryMessageBuffer = null;
                _binaryLength = 0;
            }
            callback.failed(t);
        }
    }

    @Override
    public boolean isDemanding()
    {
        return false;
    }
}
