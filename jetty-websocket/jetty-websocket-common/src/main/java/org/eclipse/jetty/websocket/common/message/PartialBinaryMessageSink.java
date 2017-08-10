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

import java.util.function.Function;

import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.MessageSink;

public class PartialBinaryMessageSink implements MessageSink
{
    private final Function<PartialBinaryMessage, Void> onBinaryFunction;

    public PartialBinaryMessageSink(Function<PartialBinaryMessage, Void> function)
    {
        this.onBinaryFunction = function;
    }
    
    @Override
    public void accept(Frame frame, FrameCallback callback)
    {
        try
        {
            onBinaryFunction.apply(new PartialBinaryMessage(frame.getPayload(), frame.isFin()));
            callback.succeed();
        }
        catch(Throwable t)
        {
            callback.fail(t);
        }
    }
}
