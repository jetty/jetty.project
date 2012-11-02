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

package org.eclipse.jetty.websocket.core.io;

import java.io.IOException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.WebSocketException;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * Utility class to pipe {@link IncomingFrames} and {@link OutgoingFrames} around
 */
public class FramePipes
{
    private static class In2Out implements IncomingFrames
    {
        private static final Logger LOG = Log.getLogger(In2Out.class);
        private OutgoingFrames outgoing;

        public In2Out(OutgoingFrames outgoing)
        {
            this.outgoing = outgoing;
        }

        @Override
        public void incoming(WebSocketException e)
        {
            /* cannot send exception on */
        }

        @Override
        public void incoming(WebSocketFrame frame)
        {
            try
            {
                this.outgoing.output(null,new FutureCallback<>(),frame);
            }
            catch (IOException e)
            {
                LOG.debug(e);
            }
        }
    }

    private static class Out2In implements OutgoingFrames
    {
        private IncomingFrames incoming;

        public Out2In(IncomingFrames incoming)
        {
            this.incoming = incoming;
        }

        @Override
        public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
        {
            this.incoming.incoming(frame);
        }
    }

    public static OutgoingFrames to(final IncomingFrames incoming)
    {
        return new Out2In(incoming);
    }

    public static IncomingFrames to(final OutgoingFrames outgoing)
    {
        return new In2Out(outgoing);
    }
}
