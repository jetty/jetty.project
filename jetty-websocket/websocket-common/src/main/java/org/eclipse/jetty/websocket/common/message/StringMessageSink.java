//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.function.Function;

import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

public class StringMessageSink implements MessageSink
{
    private final WebSocketPolicy policy;
    private final Function<String, Void> onMessageFunction;
    private Utf8StringBuilder utf;
    private int size = 0;

    public StringMessageSink(WebSocketPolicy policy, Function<String, Void> onMessageFunction)
    {
        this.policy = policy;
        this.onMessageFunction = onMessageFunction;
        size = 0;
    }

    @Override
    public void accept(ByteBuffer payload, Boolean fin)
    {
        try
        {
            if (payload != null)
            {
                policy.assertValidTextMessageSize(size + payload.remaining());
                size += payload.remaining();

                if (utf == null)
                    utf = new Utf8StringBuilder(1024);

                // allow for fast fail of BAD utf (incomplete utf will trigger on messageComplete)
                utf.append(payload);
            }
        }
        finally
        {
            if (fin)
            {
                // notify event
                onMessageFunction.apply(utf.toString());
                // reset
                size = 0;
                utf = null;
            }
        }
    }
}
