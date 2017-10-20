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
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.MessageSinkImpl;
import org.eclipse.jetty.websocket.common.util.Utf8PartialBuilder;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

public class PartialTextMessageSink extends MessageSinkImpl
{
    private final Utf8PartialBuilder utf8Partial;

    public PartialTextMessageSink(WebSocketPolicy policy, Executor executor, MethodHandle methodHandle)
    {
        super(policy, executor, methodHandle);
        this.utf8Partial = new Utf8PartialBuilder();
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        String partialText = utf8Partial.toPartialString(frame.getPayload());
        try
        {
            methodHandle.invoke(partialText, frame.isFin());
            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
                utf8Partial.reset();
        }
    }
}
