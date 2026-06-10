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

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Promise;

public class DefaultFlowController implements FlowController
{
    private final AtomicLong sessionConsumed = new AtomicLong();

    @Override
    public void onStreamCreated(Stream stream)
    {
    }

    @Override
    public void onStreamTerminated(Stream stream)
    {
    }

    @Override
    public void onDataReceived(Session session, Stream stream, long offset)
    {
    }

    @Override
    public void onDataConsumed(Session session, Stream stream, long offset)
    {
        QuicSession quicSession = (QuicSession)session;

        QuicConfiguration quicConfiguration = quicSession.getQuicConfiguration();
        long sessionBudget = quicConfiguration.getSessionMaxData();

        if (Atomics.updateMax(sessionConsumed, offset))
        {
            long max = quicSession.getRecvMaxOffset();
            long newMax = offset + sessionBudget;
            long received = quicSession.getRecvOffset();

            boolean needsMore = newMax > max;
            boolean hasEnough = max - received > sessionBudget / 2;
            if (needsMore && !hasEnough)
                quicSession.maxData(new MaxDataFrame(newMax), Promise.Invocable.noop());
        }

        // TODO: same for streams.

    }

    public static class Factory implements FlowController.Factory
    {
        @Override
        public FlowController newFlowController()
        {
            return new DefaultFlowController();
        }
    }
}
