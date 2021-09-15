//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.messages;

import java.lang.invoke.MethodHandle;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;

public class PartialByteBufferMessageSink extends AbstractMessageSink
{
    public PartialByteBufferMessageSink(CoreSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (frame.hasPayload() || frame.isFin())
                methodHandle.invoke(frame.getPayload(), frame.isFin());

            callback.succeeded();
            session.demand(1);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }
}
