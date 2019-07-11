//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.common.messages;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;

public class PartialStringMessageSink extends AbstractMessageSink
{
    private static final Logger LOG = Log.getLogger(PartialStringMessageSink.class);
    private Utf8StringBuilder utf;
    private int size;

    public PartialStringMessageSink(JavaxWebSocketSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
        Objects.requireNonNull(methodHandle, "MethodHandle");
        this.size = 0;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (utf == null)
                utf = new Utf8StringBuilder(1024);

            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();

                //TODO we should fragment on maxTextMessageBufferSize not limit
                //TODO also for PartialBinaryMessageSink
                /*
                if ((session.getMaxTextMessageBufferSize() > 0) && (size + payload.remaining() > session.getMaxTextMessageBufferSize()))
                {
                    throw new MessageTooLargeException(String.format("Binary message too large: (actual) %,d > (configured max text buffer size) %,d",
                            size + payload.remaining(), session.getMaxTextMessageBufferSize()));
                }
                */

                size += payload.remaining();

                if (LOG.isDebugEnabled())
                    LOG.debug("Raw Payload {}", BufferUtil.toDetailString(payload));

                // allow for fast fail of BAD utf
                utf.append(payload);
            }

            if (frame.isFin())
            {
                // Using toString to trigger failure on incomplete UTF-8
                methodHandle.invoke(utf.toString(), true);
                // reset
                size = 0;
                utf = null;
            }
            else
            {
                methodHandle.invoke(utf.takePartialString(), false);
            }

            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }
}
