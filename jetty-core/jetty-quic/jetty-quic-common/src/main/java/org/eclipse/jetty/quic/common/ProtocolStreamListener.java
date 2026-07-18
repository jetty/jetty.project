//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import java.util.function.Supplier;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;

/// The QUIC [Stream.Listener] that wraps a QUIC [Stream] with a [StreamEndPoint]
/// so that upper layer protocols may install a [Connection] over the `StreamEndPoint`
/// to read and parse the upper layer protocol.
public abstract class ProtocolStreamListener implements Stream.Listener
{
    protected abstract StreamEndPoint getStreamEndPoint();

    @Override
    public void onDataAvailable(Stream stream, boolean immediate)
    {
        getStreamEndPoint().fillable();
    }

    /// The [ProtocolStreamListener] implementation that wraps a local [Stream].
    public static class Local extends ProtocolStreamListener
    {
        private final Supplier<StreamEndPoint> endPoint;

        public Local(Supplier<StreamEndPoint> endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        protected StreamEndPoint getStreamEndPoint()
        {
            return endPoint.get();
        }
    }

    /// The [ProtocolStreamListener] implementation that wraps a remote [Stream].
    public static class Remote extends ProtocolStreamListener
    {
        private final ProtocolSession protocolSession;
        private StreamEndPoint endPoint;

        public Remote(ProtocolSession protocolSession)
        {
            this.protocolSession = protocolSession;
        }

        @Override
        protected StreamEndPoint getStreamEndPoint()
        {
            return endPoint;
        }

        @Override
        public void onNewStream(Stream stream, Frame.WithStreamId frame)
        {
            endPoint = protocolSession.createStreamEndPoint(stream, protocolSession::openStreamEndPoint);
        }
    }
}
