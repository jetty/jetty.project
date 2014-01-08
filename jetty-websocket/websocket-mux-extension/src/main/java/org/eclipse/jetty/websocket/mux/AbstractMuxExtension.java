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

package org.eclipse.jetty.websocket.mux;

import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

/**
 * Multiplexing Extension for WebSockets.
 * <p>
 * Supporting <a href="https://tools.ietf.org/html/draft-ietf-hybi-websocket-multiplexing-08">draft-ietf-hybi-websocket-multiplexing-08</a> Specification.
 */
public abstract class AbstractMuxExtension extends AbstractExtension
{
    private Muxer muxer;

    public AbstractMuxExtension()
    {
        super();
    }

    public abstract void configureMuxer(Muxer muxer);

    @Override
    public void incomingFrame(Frame frame)
    {
        this.muxer.incomingFrame(frame);
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        /* do nothing here, allow Muxer to handle this aspect */
    }

    @Override
    public void setConnection(LogicalConnection connection)
    {
        super.setConnection(connection);
        if (muxer != null)
        {
            throw new RuntimeException("Cannot reset muxer physical connection once established");
        }
        this.muxer = new Muxer(connection);
        configureMuxer(this.muxer);
    }
}
