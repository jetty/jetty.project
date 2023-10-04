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
import org.eclipse.jetty.websocket.core.util.MethodHolder;

public class PartialByteBufferMessageSink extends org.eclipse.jetty.websocket.core.messages.PartialByteBufferMessageSink
{
    public PartialByteBufferMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);

        /*
        TODO:
            MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class, boolean.class, Callback.class);
            if (methodHolder.type() != onMessageType)
                throw InvalidSignatureException.build(onMessageType, methodHolder.type());
         */
    }

    @Override
    protected void invoke(MethodHolder methodHolder, ByteBuffer byteBuffer, boolean fin, org.eclipse.jetty.util.Callback callback) throws Throwable
    {
        methodHolder.invoke(byteBuffer, fin, Callback.from(callback::succeeded, callback::failed));
    }
}
