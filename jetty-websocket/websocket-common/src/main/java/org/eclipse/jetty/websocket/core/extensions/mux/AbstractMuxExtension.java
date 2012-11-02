//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.extensions.mux;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.api.Extension;
import org.eclipse.jetty.websocket.core.api.WebSocketConnection;
import org.eclipse.jetty.websocket.core.api.WebSocketException;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * Multiplexing Extension for WebSockets.
 * <p>
 * Supporting <a href="https://tools.ietf.org/html/draft-ietf-hybi-websocket-multiplexing-08">draft-ietf-hybi-websocket-multiplexing-08</a> Specification.
 */
public abstract class AbstractMuxExtension extends Extension
{
    private Muxer muxer;

    public AbstractMuxExtension()
    {
        super();
    }

    public abstract void configureMuxer(Muxer muxer);

    @Override
    public void incoming(WebSocketException e)
    {
        muxer.incoming(e);
    }

    @Override
    public void incoming(WebSocketFrame frame)
    {
        muxer.incoming(frame);
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws java.io.IOException
    {
        nextOutput(context,callback,frame);
    }

    @Override
    public void setConnection(WebSocketConnection connection)
    {
        super.setConnection(connection);
        if (muxer != null)
        {
            throw new RuntimeException("Cannot reset muxer physical connection once established");
        }
        this.muxer = new Muxer(connection,this);
        configureMuxer(this.muxer);
    }
}
