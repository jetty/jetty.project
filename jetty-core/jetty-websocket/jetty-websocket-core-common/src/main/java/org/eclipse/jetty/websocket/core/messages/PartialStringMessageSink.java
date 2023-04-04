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

package org.eclipse.jetty.websocket.core.messages;

import java.lang.invoke.MethodHandle;
import java.util.Objects;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;

public class PartialStringMessageSink extends AbstractMessageSink
{
    private Utf8StringBuilder out;

    public PartialStringMessageSink(CoreSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
        Objects.requireNonNull(methodHandle, "MethodHandle");
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (out == null)
                out = new Utf8StringBuilder(session.getInputBufferSize());

            out.append(frame.getPayload());
            if (frame.isFin())
            {
                String complete = out.takeCompleteString(() -> new BadPayloadException("Invalid UTF-8"));
                methodHandle.invoke(complete, true);
                out = null;
            }
            else
            {
                String partial = out.takePartialString(() -> new BadPayloadException("Invalid UTF-8"));
                methodHandle.invoke(partial, false);
            }

            callback.succeeded();
            session.demand(1);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }
}
