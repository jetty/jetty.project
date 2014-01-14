//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.mux.MuxChannel;
import org.eclipse.jetty.websocket.mux.Muxer;

/**
 * Take {@link ResponseInfo} objects and convert to bytes for response.
 */
public class HttpTransportOverMux implements HttpTransport
{
    private static final Logger LOG = Log.getLogger(HttpTransportOverMux.class);
    private final BlockingCallback streamBlocker = new BlockingCallback();

    public HttpTransportOverMux(Muxer muxer, MuxChannel channel)
    {
        // TODO Auto-generated constructor stub
    }

    @Override
    public void completed()
    {
        LOG.debug("completed");
    }


    @Override
    public void send(ResponseInfo info, ByteBuffer responseBodyContent, boolean lastContent, Callback callback)
    {
        if (lastContent == false)
        {
            // throw error
        }

        if (info.getContentLength() > 0)
        {
            // throw error
        }

        // prepare the AddChannelResponse
        // TODO: look at HttpSender in jetty-client for generator loop logic
    }
    
    @Override
    public void send(ByteBuffer responseBodyContent, boolean lastContent, Callback callback)
    {
        send(null,responseBodyContent, lastContent, callback);
    }
}
