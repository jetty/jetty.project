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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.io.IOException;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A utility implementation of FrameHandler that defragments
 * text frames into a String message before calling {@link #onText(String,Callback)}.
 * Flow control is by default automatic, but an implementation
 * may extend {@link #isDemanding()} to return true and then explicityly control
 * demand with calls to {@link org.eclipse.jetty.websocket.core.FrameHandler.CoreSession#demand(long)}
 */
public class TextMessageHandler implements FrameHandler
{
    public static TextMessageHandler from(BiFunction<String,Callback,Void> onText)
    {
        return new TextMessageHandler()
        {
            @Override
            protected void onText(String message, Callback callback)
            {
                onText.apply(message,callback);
            }
        };
    }


    public static TextMessageHandler from(Consumer<String> onText)
    {
        return new TextMessageHandler()
        {
            @Override
            protected void onText(String message, Callback callback)
            {
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
        };
    }

    private Logger LOG = Log.getLogger(TextMessageHandler.class);

    private CoreSession coreSession;
    private Utf8StringBuilder utf8;

    @Override
    public void onOpen(CoreSession coreSession) throws Exception
    {
        this.coreSession = coreSession;
        final int maxSize = coreSession.getPolicy().getMaxTextMessageSize();

        this.utf8 = (maxSize < 0) ? new Utf8StringBuilder() : new Utf8StringBuilder()
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
                    callback.failed(new BadPayloadException("Text messages only"));
                    break;

                case OpCode.TEXT:
                case OpCode.CONTINUATION:
                    utf8.append(frame.getPayload());
                    if (frame.isFin())
                    {
                        String message = utf8.toString();
                        utf8.reset();
                        onText(message, callback);
                    }
                    else
                    {
                        if (isDemanding())
                            getCoreSession().demand(1);
                        callback.succeeded();
                    }
                    break;

                default:
                    throw new IllegalStateException();
            }
        }
        catch(Utf8Appendable.NotUtf8Exception bple)
        {
            utf8.reset();
            callback.failed(new BadPayloadException(bple));
        }
        catch(Throwable th)
        {
            utf8.reset();
            callback.failed(th);
        }
    }

    /**
     * Method called when a complete text message is received.
     *
     * @param message The message.
     */
    protected void onText(String message, Callback callback)
    {
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

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onClosed {}", this, closeStatus);
        if (utf8.length()>0 && closeStatus.isNormal())
            LOG.warn("{} closed with partial message: {} chars", utf8.length());
        utf8.reset();
        utf8 = null;
        coreSession = null;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug(this + " onError ", cause);
    }
}
