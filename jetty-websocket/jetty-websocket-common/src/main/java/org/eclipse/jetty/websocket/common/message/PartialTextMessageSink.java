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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.MessageSink;
import org.eclipse.jetty.websocket.common.util.Utf8PartialBuilder;
import org.eclipse.jetty.websocket.core.Frame;

public class PartialTextMessageSink implements MessageSink
{
    private final Function<PartialTextMessage, Void> onTextFunction;
    private final Utf8PartialBuilder utf8Partial;

    public PartialTextMessageSink(Function<PartialTextMessage, Void> function)
    {
        this.onTextFunction = function;
        this.utf8Partial = new Utf8PartialBuilder();
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        String partialText = utf8Partial.toPartialString(frame.getPayload());
        try
        {
            onTextFunction.apply(new PartialTextMessage(partialText,frame.isFin()));
            callback.succeeded();
        }
        catch(Throwable t)
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
