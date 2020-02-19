//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.message;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.AbstractMessageSink;
import org.eclipse.jetty.websocket.common.invoke.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringMessageSink extends AbstractMessageSink
{
    private static final Logger LOG = LoggerFactory.getLogger(StringMessageSink.class);
    private final Session session;
    private Utf8StringBuilder utf;
    private int size = 0;

    public StringMessageSink(Executor executor, MethodHandle methodHandle, Session session)
    {
        super(executor, methodHandle);
        this.session = session;

        // Validate onMessageMethod
        Objects.requireNonNull(methodHandle, "MethodHandle");
        MethodType onMessageType = MethodType.methodType(Void.TYPE, String.class);
        if (methodHandle.type() != onMessageType)
        {
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
        }

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
                size = size + payload.remaining();
                long maxMessageSize = session.getMaxTextMessageSize();
                if (maxMessageSize > 0 && size > maxMessageSize)
                    throw new MessageTooLargeException("Message size [" + size + "] exceeds maximum size [" + maxMessageSize + "]");

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
                    methodHandle.invoke(utf.toString());
                else
                    methodHandle.invoke("");

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
