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

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.Promise;

/// The default implementation for the controller of the maximum number of streams
/// of a session.
///
/// This implementation sends a [MaxStreamsFrame] incrementing the previous max
/// value by a constant budget, when the following conditions are both `true`:
///
/// * The remote peer has closed at least one stream since the last update.
/// * The remote peer can open a number of streams less than half the budget.
///
/// A peer only opening streams, and not closing them, will not get an update.
///
/// A peer normally opening and closing streams will get an update when half
/// of the streams have been opened, leaving room to open the other half while
/// the update is traveling to the peer.
///
/// The constant budget is advertised by the remote peer via the
/// [TransportParameters.Ids#INITIAL_MAX_STREAMS_UNIDIRECTIONAL] and
/// [TransportParameters.Ids#INITIAL_MAX_STREAMS_BIDIRECTIONAL] parameters.
/// In case the parameter is missing, `0` is assumed.
public class DefaultStreamsController implements StreamsController
{
    private final AtomicLong biClosed = new AtomicLong();
    private final AtomicLong uniClosed = new AtomicLong();

    @Override
    public void onStreamCreated(Stream stream)
    {
    }

    @Override
    public void onStreamTerminated(Stream stream)
    {
        boolean bidirectional = stream.isBidirectional();
        QuicSession session = (QuicSession)stream.getSession();

        long budget = session.getMaxStreams(bidirectional);
        long max = bidirectional ? session.getBidirectionalRemoteStreamMaxCount() : session.getUnidirectionalRemoteStreamMaxCount();
        long opened = bidirectional ? session.getBidirectionalRemoteStreamCount() : session.getUnidirectionalRemoteStreamCount();
        long closed = (bidirectional ? biClosed : uniClosed).incrementAndGet();
        long newMax = closed + budget;

        boolean needsMore = newMax > max;
        boolean hasEnough = max - opened > budget / 2;
        if (needsMore && !hasEnough)
            session.maxStreams(new MaxStreamsFrame(newMax, bidirectional), Promise.Invocable.noop());
    }

    public static class Factory implements StreamsController.Factory
    {
        @Override
        public StreamsController newStreamsController()
        {
            return new DefaultStreamsController();
        }
    }
}
