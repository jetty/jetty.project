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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A utility implementation of FrameHandler that defragments
 * text frames into a String message before calling {@link #onText(String,Callback)}.
 * Flow control is by default automatic, but an implementation
 * may extend {@link #isDemanding()} to return true and then explicityly control
 * demand with calls to {@link org.eclipse.jetty.websocket.core.FrameHandler.CoreSession#demand(long)}
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
                    super.onBinary(message,callback);
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


    private Logger LOG = Log.getLogger(MessageHandler.class);

    private final int factor;

    private CoreSession coreSession;
    private Utf8StringBuilder utf8StringBuilder = null;
    private ByteBuffer binaryMessage = null;
    private byte dataType = OpCode.UNDEFINED;


    public MessageHandler()
    {
        this(3);
    }

    public MessageHandler(int factor)
    {
        this.factor = factor;
    }

    @Override
    public void onOpen(CoreSession coreSession) throws Exception
    {
        this.coreSession = coreSession;
    }

    public CoreSession getCoreSession()
    {
        return coreSession;
    }

    @Override
    public void onReceiveFrame(Frame frame, Callback callback)
    {
        try
        {
            byte opcode = frame.getOpCode();
            if (LOG.isDebugEnabled())
                LOG.debug("{}: {}", OpCode.name(opcode), BufferUtil.toDetailString(frame.getPayload()));

            switch (opcode)
            {
                case OpCode.PING:
                case OpCode.PONG:
                case OpCode.CLOSE:
                    if (isDemanding())
                        getCoreSession().demand(1);
                    callback.succeeded();
                    break;

                case OpCode.BINARY:
                    if (frame.isFin())
                    {
                        final int maxSize = coreSession.getPolicy().getMaxBinaryMessageSize();
                        if (frame.hasPayload() && frame.getPayload().remaining()>maxSize)
                            throw new MessageTooLargeException("Message larger than " + maxSize + " bytes");

                        onBinary(frame.getPayload(), callback); //bypass buffer aggregation
                    }
                    else
                    {
                        dataType = OpCode.BINARY;
                        binaryMessage = getCoreSession().getByteBufferPool().acquire(frame.getPayloadLength() * factor, false);
                        onBinaryFrame(frame, callback);
                    }
                    break;

                case OpCode.TEXT:
                    dataType = OpCode.TEXT;
                    if (utf8StringBuilder == null)
                    {
                        final int maxSize = coreSession.getPolicy().getMaxTextMessageSize();
                        utf8StringBuilder = (maxSize < 0) ? new Utf8StringBuilder() : new Utf8StringBuilder()
                        {
                            @Override
                            protected void appendByte(byte b) throws IOException
                            {
                                // TODO can we avoid byte by byte length check?
                                if (length() >= maxSize)
                                    throw new MessageTooLargeException("Message larger than " + maxSize + " characters");
                                super.appendByte(b);
                            }
                        };
                    }
                    onTextFrame(frame, callback);
                    break;

                case OpCode.CONTINUATION:
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
                    break;

                default:
                    throw new IllegalStateException();
            }
        }
        catch(Utf8Appendable.NotUtf8Exception bple)
        {
            utf8StringBuilder.reset();
            callback.failed(new BadPayloadException(bple));
        }
        catch(Throwable th)
        {
            if (utf8StringBuilder != null)
                utf8StringBuilder.reset();

            callback.failed(th);
        }
    }

    private void onTextFrame(Frame frame, Callback callback)
    {
        if (frame.hasPayload())
            utf8StringBuilder.append(frame.getPayload());

        if (frame.isFin())
        {
            dataType = OpCode.UNDEFINED;

            String message = utf8StringBuilder.toString();
            utf8StringBuilder.reset();
            onText(message, callback);
        }
        else
        {
            if (isDemanding())
                getCoreSession().demand(1);
            callback.succeeded();
        }
    }

    private void onBinaryFrame(Frame frame, Callback callback)
    {
        if (frame.hasPayload())
        {
            if (BufferUtil.space(binaryMessage) < frame.getPayloadLength())
                binaryMessage = BufferUtil.ensureCapacity(binaryMessage,binaryMessage.capacity()+Math.max(binaryMessage.capacity(), frame.getPayloadLength()*factor));

            BufferUtil.append(binaryMessage,frame.getPayload());
        }

        final int maxSize = coreSession.getPolicy().getMaxBinaryMessageSize();
        if (binaryMessage.remaining()>maxSize)
        {
            getCoreSession().getByteBufferPool().release(binaryMessage);
            binaryMessage = null;
            throw new MessageTooLargeException("Message larger than " + maxSize + " bytes");
        }

        if (frame.isFin())
        {
            dataType = OpCode.UNDEFINED;

            final ByteBuffer completeMessage = binaryMessage;
            binaryMessage = null;

            callback = new Callback.Nested(callback)
            {
                @Override
                public void completed()
                {
                    getCoreSession().getByteBufferPool().release(completeMessage);
                }
            };

            onBinary(completeMessage, callback);
        }
        else
        {
            if (isDemanding())
                getCoreSession().demand(1);
            callback.succeeded();
        }
    }


    /**
     * Method called when a complete text message is received.
     *
     * @param message
     * @param callback
     */
    protected void onText(String message, Callback callback)
    {
        callback.failed(new BadPayloadException("Text Not Accepted"));
    }

    /**
     * Method called when a complete binary message is received.
     * @param message
     * @param callback
     */
    protected void onBinary(ByteBuffer message, Callback callback)
    {
        callback.failed(new BadPayloadException("Binary Not Accepted"));
    }

    /**
     * Send a String as a single text frame.
     * @param message The message to send
     * @param callback The callback to call when the send is complete
     * @param mode The batch mode to send the frames in.
     */
    public void sendText(String message, Callback callback, BatchMode mode)
    {
        getCoreSession().sendFrame(new Frame(OpCode.TEXT,true,message),callback,mode);
    }

    /**
     * Send a sequence of Strings as a sequences for fragmented text frame.
     * Sending a large message in fragments can reduce memory overheads as only a
     * single fragment need be converted to bytes
     * @param callback The callback to call when the send is complete
     * @param mode The batch mode to send the frames in.
     * @param parts The parts of the message.
     */
    public void sendText(Callback callback, BatchMode mode, final String... parts)
    {
        if (parts==null || parts.length==0)
        {
            callback.succeeded();
            return;
        }

        if (parts.length==1)
        {
            sendText(parts[0],callback,mode);
            return;
        }

        new IteratingNestedCallback(callback)
        {
            int i = 0;
            @Override
            protected Action process() throws Throwable
            {
                if (i+1>parts.length)
                    return Action.SUCCEEDED;

                String part = parts[i++];
                getCoreSession().sendFrame(new Frame(
                    i==1?OpCode.TEXT:OpCode.CONTINUATION,
                    i==parts.length, part), this, mode);
                return Action.SCHEDULED;
            }
        }.iterate();
    }


    /**
     * Send a ByteBuffer as a single binary frame.
     * @param message The message to send
     * @param callback The callback to call when the send is complete
     * @param mode The batch mode to send the frames in.
     */
    public void sendBinary(ByteBuffer message, Callback callback, BatchMode mode)
    {
        getCoreSession().sendFrame(new Frame(OpCode.BINARY,true, message),callback,mode);
    }

    /**
     * Send a sequence of ByteBuffers as a sequences for fragmented text frame.
     *
     * @param callback The callback to call when the send is complete
     * @param mode The batch mode to send the frames in.
     * @param parts The parts of the message.
     */
    public void sendBinary(Callback callback, BatchMode mode, final ByteBuffer... parts)
    {
        if (parts==null || parts.length==0)
        {
            callback.succeeded();
            return;
        }

        if (parts.length==1)
        {
            sendBinary(parts[0],callback,mode);
            return;
        }

        new IteratingNestedCallback(callback)
        {
            int i = 0;
            @Override
            protected Action process() throws Throwable
            {
                if (i+1>parts.length)
                    return Action.SUCCEEDED;

                ByteBuffer part = parts[i++];
                getCoreSession().sendFrame(new Frame(
                        i==1?OpCode.BINARY:OpCode.CONTINUATION,
                        i==parts.length, part), this, mode);
                return Action.SCHEDULED;
            }
        }.iterate();
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onClosed {}", this, closeStatus);
        if (utf8StringBuilder!=null && utf8StringBuilder.length()>0 && closeStatus.isNormal())
            LOG.warn("{} closed with partial message: {} chars", utf8StringBuilder.length());


        if (binaryMessage != null)
        {
            if (BufferUtil.hasContent(binaryMessage))
                LOG.warn("{} closed with partial message: {} bytes", binaryMessage.remaining());

            getCoreSession().getByteBufferPool().release(binaryMessage);
            binaryMessage = null;
        }

        if (utf8StringBuilder!=null)
        {
            utf8StringBuilder.reset();
            utf8StringBuilder = null;
        }
        coreSession = null;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug(this + " onError ", cause);
    }
}
