//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.Executor;

import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;

public class DecodedReaderMessageSink extends ReaderMessageSink
{
    private final Decoder.TextStream decoder;

    public DecodedReaderMessageSink(JavaxWebSocketSession session, Decoder.TextStream decoder, MethodHandle methodHandle)
    {
        this(session.getPolicy(), session.getContainerContext().getExecutor(), decoder, methodHandle);
    }

    public DecodedReaderMessageSink(WebSocketPolicy policy, Executor executor, Decoder.TextStream decoder, MethodHandle methodHandle)
    {
        super(policy, executor, methodHandle);
        this.decoder = decoder;

        /*super(executor, (reader) ->
        {
            try
            {
                Object decoded = decoder.decode(reader);
    
                // notify event
                Object ret = onMessageFunction.apply(decoded);
                
                if (ret != null)
                {
                    // send response
                    session.getBasicRemote().sendObject(ret);
                }
                
                return null;
            }
            catch (DecodeException | EncodeException | IOException e)
            {
                throw new WebSocketException(e);
            }
        });*/
    }
}
