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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EncodeException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.jsr356.JsrSession;

public class DecodedReaderMessageSink extends ReaderMessageSink
{
    private static final Logger LOG = Log.getLogger(DecodedReaderMessageSink.class);
    
    public DecodedReaderMessageSink(WSLocalEndpoint<JsrSession> endpointFunctions, Executor executor, Decoder.TextStream decoder, Function<Object, Object> onMessageFunction)
    {
        super(executor, (reader) ->
        {
            try
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("{}.decode((Reader){})", decoder.getClass().getName(), reader);
                Object decoded = decoder.decode(reader);
    
                if(LOG.isDebugEnabled())
                    LOG.debug("onMessageFunction/{}/.apply({})", onMessageFunction, decoded);
                // notify event
                Object ret = onMessageFunction.apply(decoded);
                
                if(LOG.isDebugEnabled())
                    LOG.debug("ret = {}", ret);
                
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
        });
    }
}
