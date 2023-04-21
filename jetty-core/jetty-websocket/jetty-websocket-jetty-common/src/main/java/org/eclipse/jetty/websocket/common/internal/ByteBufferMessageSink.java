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
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;

public class ByteBufferMessageSink extends org.eclipse.jetty.websocket.core.messages.ByteBufferMessageSink
{
    public ByteBufferMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        super(session, methodHandle, autoDemand, false);

        MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class, Callback.class);
        if (methodHandle.type() != onMessageType)
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
    }

    @Override
    protected void invoke(MethodHandle methodHandle, ByteBuffer byteBuffer, org.eclipse.jetty.util.Callback callback) throws Throwable
    {
        methodHandle.invoke(byteBuffer, Callback.from(callback::succeeded, callback::failed));
    }
}
