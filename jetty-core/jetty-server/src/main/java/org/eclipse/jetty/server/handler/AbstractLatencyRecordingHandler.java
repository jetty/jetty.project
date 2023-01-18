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

package org.eclipse.jetty.server.handler;

import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A <code>Handler</code> that allows recording the latency of the requests executed by the wrapped handler.</p>
 * <p>The latency reported by {@link #onRequestComplete(long)} is the delay between the first notice of the request
 * (obtained from {@link HttpStream#getNanoTime()}) until the stream completion event has been handled by
 * {@link HttpStream#succeeded()} or {@link HttpStream#failed(Throwable)}.</p>
 */
public abstract class AbstractLatencyRecordingHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLatencyRecordingHandler.class);

    private final Function<HttpStream, HttpStream> recordingWrapper;

    public AbstractLatencyRecordingHandler()
    {
        this.recordingWrapper = httpStream -> new HttpStream.Wrapper(httpStream)
        {
            @Override
            public void succeeded()
            {
                super.succeeded();
                fireOnRequestComplete();
            }

            @Override
            public void failed(Throwable x)
            {
                super.failed(x);
                fireOnRequestComplete();
            }

            private void fireOnRequestComplete()
            {
                try
                {
                    onRequestComplete(NanoTime.since(httpStream.getNanoTime()));
                }
                catch (Throwable t)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Error thrown by onRequestComplete", t);
                }
            }
        };
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        request.addHttpStreamWrapper(recordingWrapper);
        return super.process(request, response, callback);
    }

    /**
     * Called back for each completed request with its execution's latency.
     * @param durationInNs the duration in nanoseconds of the completed request
     */
    protected abstract void onRequestComplete(long durationInNs);
}
