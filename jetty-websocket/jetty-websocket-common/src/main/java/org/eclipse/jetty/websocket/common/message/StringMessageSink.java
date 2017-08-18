//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.MessageSink;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.invoke.InvalidSignatureException;

public class StringMessageSink implements MessageSink
{
    private static final Logger LOG = Log.getLogger(StringMessageSink.class);
    private WSPolicy policy;
    private final MethodHandle onMessageHandle;
    private Utf8StringBuilder utf;
    private int size = 0;

    public StringMessageSink(WSPolicy policy, MethodHandle methodHandle) throws InvalidSignatureException
    {
        // Validate onMessageMethod
        Objects.requireNonNull(methodHandle, "MethodHandle");
        MethodType onMessageType = MethodType.methodType(Void.TYPE, String.class);
        if (methodHandle.type() != onMessageType)
        {
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
        }

        this.policy = policy;
        this.onMessageHandle = methodHandle;
        this.size = 0;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                policy.assertValidTextMessageSize(size + payload.remaining());
                size += payload.remaining();

                if (utf == null)
                    utf = new Utf8StringBuilder(1024);

                if (LOG.isDebugEnabled())
                    LOG.debug("Raw Payload {}", BufferUtil.toDetailString(payload));

                // allow for fast fail of BAD utf (incomplete utf will trigger on messageComplete)
                utf.append(payload);
            }

            if (frame.isFin())
            {
                // notify event
                if (utf != null)
                    onMessageHandle.invoke(utf.toString());
                else
                    onMessageHandle.invoke("");

                // reset
                size = 0;
                utf = null;
            }

            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }
}
