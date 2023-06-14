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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.NanoTime;

/**
 * <p>A <code>Handler</code> that helps recording the total latency of the requests executed by the wrapped handler.</p>
 * <p>The latency reported by {@link #onRequestComplete(String, long)} is the delay between when {@link Request#getBeginNanoTime()
 * the request arrived to a connector} until {@link EventsHandler#onComplete(Request, Throwable) the completion of that
 * request}.</p>
 */
public abstract class LatencyRecordingHandler extends EventsHandler
{
    public LatencyRecordingHandler()
    {
    }

    public LatencyRecordingHandler(Handler handler)
    {
        super(handler);
    }

    @Override
    protected final void onComplete(Request request, Throwable failure)
    {
        onRequestComplete(request.getId(), NanoTime.since(request.getBeginNanoTime()));
    }

    /**
     * Called back for each completed request with its execution's latency.
     *
     * @param requestId the ID of the request
     * @param durationInNs the duration in nanoseconds of the completed request
     */
    protected abstract void onRequestComplete(String requestId, long durationInNs);
}
