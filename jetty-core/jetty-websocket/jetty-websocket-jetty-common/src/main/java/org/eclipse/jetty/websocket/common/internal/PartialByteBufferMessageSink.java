//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.messages.AbstractMessageSink;

public class PartialByteBufferMessageSink extends AbstractMessageSink
{
    public PartialByteBufferMessageSink(CoreSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);

        MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class, boolean.class, Callback.class);
        if (methodHandle.type() != onMessageType)
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
    }

    @Override
    public void accept(Frame frame, org.eclipse.jetty.util.Callback callback)
    {
        try
        {
            if (frame.hasPayload() || frame.isFin())
                methodHandle.invoke(frame.getPayload(), frame.isFin(), Callback.from(callback::succeeded, callback::failed));
            else
                callback.succeeded();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }
}
