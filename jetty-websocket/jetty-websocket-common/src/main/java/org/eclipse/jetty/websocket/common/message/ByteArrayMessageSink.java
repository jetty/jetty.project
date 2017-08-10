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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.MessageSink;

public class ByteArrayMessageSink implements MessageSink
{
    private static final byte EMPTY_BUFFER[] = new byte[0];
    private static final int BUFFER_SIZE = 65535;
    private final WebSocketPolicy policy;
    private final Function<byte[], Void> onMessageFunction;
    private ByteArrayOutputStream out;
    private int size;

    public ByteArrayMessageSink(WebSocketPolicy policy, Function<byte[], Void> onMessageFunction)
    {
        this.policy = policy;
        this.onMessageFunction = onMessageFunction;
    }

    @Override
    public void accept(Frame frame, FrameCallback callback)
    {
        try
        {
            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                policy.assertValidBinaryMessageSize(size + payload.remaining());
                size += payload.remaining();

                if (out == null)
                    out = new ByteArrayOutputStream(BUFFER_SIZE);

                BufferUtil.writeTo(payload, out);
            }
    
            if (frame.isFin())
            {
                if (out != null)
                    notifyOnMessage(out.toByteArray());
                else
                    notifyOnMessage(EMPTY_BUFFER);
            }
    
            callback.succeed();
        }
        catch (Throwable t)
        {
            callback.fail(t);
        }
        finally
        {
            if (frame.isFin())
            {
                // reset
                out = null;
                size = 0;
            }
        }
    }

    private Object notifyOnMessage(byte buf[])
    {
        return onMessageFunction.apply(buf);
    }
}
