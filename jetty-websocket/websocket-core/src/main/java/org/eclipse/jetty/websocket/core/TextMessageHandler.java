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
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.io.IOException;

/**
 * A utility implementation of FrameHandler that aggregates
 * fragmented Text messages into a String before calling {@link #onText(String)}.
 * Since Strings are immutable, the message may be handled asynchronously or
 * asynchronously. Flow control is by default automatic, but an implementation
 * may extend {@link #isDemanding()} to return true and then explicityly control
 * demand with calls to {@link org.eclipse.jetty.websocket.core.FrameHandler.CoreSession#demand(long)}
 *
 */
public class TextMessageHandler implements FrameHandler
{
    private Logger LOG = Log.getLogger(TextMessageHandler.class);

    private CoreSession coreSession;
    private Utf8StringBuilder utf8;

    // TODO add a unit test for this class

    @Override
    public void onOpen(CoreSession coreSession) throws Exception
    {
        this.coreSession = coreSession;
        final int maxSize = coreSession.getPolicy().getMaxTextMessageSize();

        this.utf8 = (maxSize<0) ? new Utf8StringBuilder() : new Utf8StringBuilder()
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
        byte opcode = frame.getOpCode();
        if (LOG.isDebugEnabled())
            LOG.debug("{}: {}", OpCode.name(opcode), BufferUtil.toDetailString(frame.getPayload()));
        switch (opcode)
        {
            case OpCode.PING:
            case OpCode.PONG:
            case OpCode.CLOSE:
                callback.succeeded();
                if (isDemanding())
                    getCoreSession().demand(1);
                break;

            case OpCode.BINARY:
                LOG.warn("{} unhandled {}",this,frame);
                callback.succeeded();
                if (isDemanding())
                    getCoreSession().demand(1);
                break;

            case OpCode.TEXT:
                if (frame.isFin())
                {
                    if (isDemanding())
                    {
                        callback.succeeded();
                        onText(frame.getPayloadAsUTF8());
                    }
                    else
                    {
                        onText(frame.getPayloadAsUTF8());
                        callback.succeeded();
                    }
                    break;
                }

                utf8.reset();
                utf8.append(frame.getPayload());
                callback.succeeded();
                if (isDemanding())
                    getCoreSession().demand(1);
                break;

            case OpCode.CONTINUATION:
                utf8.append(frame.getPayload());
                if (frame.isFin())
                {
                    if (isDemanding())
                    {
                        callback.succeeded();
                        onText(utf8.toString());
                    }
                    else
                    {
                        onText(utf8.toString());
                        callback.succeeded();
                    }
                    break;
                }

                callback.succeeded();
                if (isDemanding())
                    getCoreSession().demand(1);

        }
    }

    /**
     * Method called when a complete text message is received.
     * @param message The message.
     */
    protected void onText(String message)
    {
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onClosed {}",this,closeStatus);
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug(this+" onError ", cause);
    }
}
