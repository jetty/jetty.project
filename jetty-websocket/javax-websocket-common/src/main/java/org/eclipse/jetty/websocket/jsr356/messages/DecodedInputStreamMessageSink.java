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

import javax.websocket.Decoder;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;

public class DecodedInputStreamMessageSink<T> extends InputStreamMessageSink
{
    private final Decoder.BinaryStream<T> decoder;

    public DecodedInputStreamMessageSink(JavaxWebSocketSession session,
                                         Decoder.BinaryStream<T> decoder,
                                         MethodHandle methodHandle)
    {
        super(session, methodHandle);
        this.decoder = decoder;
        /*(reader) ->
        {
            try
            {
                Object decoded = decoder.decode(reader);
            
                // notify event
                Object ret = onMessageFunction.apply(decoded);
            
                if (ret != null)
                {
                    // send response
                    endpointFunctions.getSession().getBasicRemote().sendObject(ret);
                }
            
                return null;
            }
            catch (DecodeException | EncodeException | IOException e)
            {
                throw new WebSocketException(e);
            }
        });*/
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        super.accept(frame, callback);
    }
}
