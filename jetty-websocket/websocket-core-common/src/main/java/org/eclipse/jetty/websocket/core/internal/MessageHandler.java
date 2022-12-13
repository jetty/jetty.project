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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility implementation of FrameHandler that defragments
 * text frames into a String message before calling {@link #onText(String, Callback)}.
 * Flow control is by default automatic, but an implementation
 * may extend {@link #isDemanding()} to return true and then explicityly control
 * demand with calls to {@link CoreSession#demand(long)}
 */
public class MessageHandler implements FrameHandler
{
    public static MessageHandler from(Consumer<String> onText, Consumer<ByteBuffer> onBinary)
    {
        return new MessageHandler()
        {
            @Override
            protected void onText(String message, Callback callback)
            {
                if (onText == null)
                {
                    super.onText(message, callback);
                    return;
                }

                try
                {
                    onText.accept(message);
                    callback.succeeded();
                }
                catch (Throwable th)
                {
                    callback.failed(th);
                }
            }

            @Override
            protected void onBinary(ByteBuffer message, Callback callback)
            {
                if (onBinary == null)
                {
                    super.onBinary(message, callback);
                    return;
                }

                try
                {
                    onBinary.accept(message);
                    callback.succeeded();
                }
                catch (Throwable th)
                {
                    callback.failed(th);
                }
            }
        };
    }

    private static final Logger LOG = LoggerFactory.getLogger(MessageHandler.class);

    private CoreSession coreSession;
    private Utf8StringBuilder textMessageBuffer;
    private ByteArrayOutputStream binaryMessageBuffer;
    private byte dataType = OpCode.UNDEFINED;

    public CoreSession getCoreSession()
    {
        return coreSession;
    }

    private Utf8StringBuilder getTextMessageBuffer()
    {
        if (textMessageBuffer == null)
            textMessageBuffer = new Utf8StringBuilder();
        return textMessageBuffer;
    }

    private ByteArrayOutputStream getBinaryMessageBuffer()
    {
        if (binaryMessageBuffer == null)
            binaryMessageBuffer = new ByteArrayOutputStream();
        return binaryMessageBuffer;
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", coreSession);

        this.coreSession = coreSession;
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

        if (textMessageBuffer != null)
        {
            textMessageBuffer.reset();
            textMessageBuffer = null;
        }

        if (binaryMessageBuffer != null)
        {
            binaryMessageBuffer.reset();
            binaryMessageBuffer = null;
        }

        callback.succeeded();
    }

    protected void onTextFrame(Frame frame, Callback callback)
    {
        try
        {
            Utf8StringBuilder textBuffer = getTextMessageBuffer();

            if (frame.hasPayload())
            {
                long maxSize = coreSession.getMaxTextMessageSize();
                long currentSize = frame.getPayload().remaining() + textBuffer.length();
                if (currentSize > maxSize)
                    throw new MessageTooLargeException("Message larger than " + maxSize + " bytes");

                textBuffer.append(frame.getPayload());
            }

            if (frame.isFin())
            {
                onText(textBuffer.toString(), callback);
                textBuffer.reset();
            }
            else
            {
                callback.succeeded();
            }
        }
        catch (Utf8Appendable.NotUtf8Exception e)
        {
            callback.failed(new BadPayloadException(e));
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }

    }

    protected void onBinaryFrame(Frame frame, Callback callback)
    {
        try
        {
            ByteArrayOutputStream binaryBuffer = getBinaryMessageBuffer();

            if (frame.hasPayload())
            {
                long maxSize = coreSession.getMaxBinaryMessageSize();
                long currentSize = frame.getPayload().remaining() + binaryBuffer.size();
                if (currentSize > maxSize)
                    throw new MessageTooLargeException("Message larger than " + maxSize + " bytes");

                BufferUtil.writeTo(frame.getPayload(), binaryBuffer);
            }

            if (frame.isFin())
            {
                onBinary(BufferUtil.toBuffer(binaryBuffer.toByteArray()), callback);
                binaryBuffer.reset();
            }
            else
            {
                callback.succeeded();
            }
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    protected void onContinuationFrame(Frame frame, Callback callback)
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

    protected void onPingFrame(Frame frame, Callback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.PONG, true, frame.getPayload()), callback, false);
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
     * Send a sequence of Strings as a sequences for fragmented text frame.
     * Sending a large message in fragments can reduce memory overheads as only a
     * single fragment need be converted to bytes
     *
     * @param callback The callback to call when the send is complete
     * @param batch    The batch mode to send the frames in.
     * @param parts    The parts of the message.
     */
    public void sendText(Callback callback, boolean batch, final String... parts)
    {
        if (parts == null || parts.length == 0)
        {
            callback.succeeded();
            return;
        }

        if (parts.length == 1)
        {
            sendText(parts[0], callback, batch);
            return;
        }

        new IteratingNestedCallback(callback)
        {
            int i = 0;

            @Override
            protected Action process() throws Throwable
            {
                if (i + 1 > parts.length)
                    return Action.SUCCEEDED;

                String part = parts[i++];
                getCoreSession().sendFrame(new Frame(
                        i == 1 ? OpCode.TEXT : OpCode.CONTINUATION,
                        i == parts.length, part), this, batch);
                return Action.SCHEDULED;
            }
        }.iterate();
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

    /**
     * Send a sequence of ByteBuffers as a sequences for fragmented text frame.
     *
     * @param callback The callback to call when the send is complete
     * @param batch    The batch mode to send the frames in.
     * @param parts    The parts of the message.
     */
    public void sendBinary(Callback callback, boolean batch, final ByteBuffer... parts)
    {
        if (parts == null || parts.length == 0)
        {
            callback.succeeded();
            return;
        }

        if (parts.length == 1)
        {
            sendBinary(parts[0], callback, batch);
            return;
        }

        new IteratingNestedCallback(callback)
        {
            int i = 0;

            @Override
            protected Action process() throws Throwable
            {
                if (i + 1 > parts.length)
                    return Action.SUCCEEDED;

                ByteBuffer part = parts[i++];
                getCoreSession().sendFrame(new Frame(
                        i == 1 ? OpCode.BINARY : OpCode.CONTINUATION,
                        i == parts.length, part), this, batch);
                return Action.SCHEDULED;
            }
        }.iterate();
    }
}
