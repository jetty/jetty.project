//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

public class ProtocolQuicSession
{
    private final AtomicLong active = new AtomicLong();
    private final QuicSession session;

    public ProtocolQuicSession(QuicSession session)
    {
        this.session = session;
    }

    public QuicSession getQuicSession()
    {
        return session;
    }

    public void process()
    {
        if (active.getAndIncrement() == 0)
        {
            session.getExecutor().execute(() ->
            {
                while (true)
                {
                    session.processWritableStreams();
                    if (session.processReadableStreams())
                        continue;
                    // Exit if did not process any stream and we are idle.
                    if (active.decrementAndGet() == 0)
                        break;
                }
            });
        }
    }

    public interface Factory
    {
        public ProtocolQuicSession newProtocolQuicSession(QuicSession quicSession);
    }
}
