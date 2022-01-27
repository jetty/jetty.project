//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Graceful;

/**
 * <p>A container that tracks {@link QuicSession} instances.</p>
 */
public class QuicSessionContainer extends AbstractLifeCycle implements QuicSession.Listener, Graceful, Dumpable
{
    private final Set<QuicSession> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();

    @Override
    public void onOpened(QuicSession session)
    {
        sessions.add(session);
    }

    @Override
    public void onClosed(QuicSession session)
    {
        sessions.remove(session);
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> existing = shutdown.compareAndExchange(null, result);
        if (existing == null)
        {
            CompletableFuture.allOf(sessions.stream().map(QuicSession::shutdown).toArray(CompletableFuture[]::new))
                .whenComplete((v, x) ->
                {
                    if (x == null)
                        result.complete(v);
                    else
                        result.completeExceptionally(x);
                });
            return result;
        }
        else
        {
            return existing;
        }
    }

    @Override
    public boolean isShutdown()
    {
        return shutdown.get() != null;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("sessions", sessions));
    }
}
