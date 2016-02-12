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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

public class ByteBufferMessageSink implements MessageSink
{
    private static final int BUFFER_SIZE = 65535;
    private final WebSocketPolicy policy;
    private final Function<ByteBuffer, Void> onMessageFunction;
    private ByteArrayOutputStream out;
    private int size;

    public ByteBufferMessageSink(WebSocketPolicy policy, Function<ByteBuffer, Void> onMessageFunction)
    {
        this.policy = policy;
        this.onMessageFunction = onMessageFunction;
    }

    @Override
    public void accept(ByteBuffer payload, Boolean fin)
    {
        try
        {
            if (payload != null)
            {
                policy.assertValidBinaryMessageSize(size + payload.remaining());
                size += payload.remaining();

                if (out == null)
                    out = new ByteArrayOutputStream(BUFFER_SIZE);

                BufferUtil.writeTo(payload,out);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to append Binary Message", e);
        }
        finally
        {
            if (fin)
            {
                ByteBuffer bbuf = ByteBuffer.wrap(out.toByteArray());
                onMessageFunction.apply(bbuf);
                // reset
                out = null;
                size = 0;
            }
        }
    }
}
