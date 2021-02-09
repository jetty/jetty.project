//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.io;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;

public class FramePipes
{
    private static class In2Out implements IncomingFrames
    {
        private OutgoingFrames outgoing;

        public In2Out(OutgoingFrames outgoing)
        {
            this.outgoing = outgoing;
        }

        @Override
        public void incomingFrame(Frame frame)
        {
            this.outgoing.outgoingFrame(frame, null, BatchMode.OFF);
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
        public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
        {
            try
            {
                this.incoming.incomingFrame(frame);
                callback.writeSuccess();
            }
            catch (Throwable t)
            {
                callback.writeFailed(t);
            }
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
