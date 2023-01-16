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
 * A <code>Handler</code> that allows recording the latencies of the requests executed by the wrapped handler.
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
                long begin = httpStream.getNanoTime();
                super.succeeded();
                try
                {
                    onRequestComplete(NanoTime.since(begin));
                }
                catch (Throwable t)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Error thrown by onRequestComplete", t);
                }
            }

            @Override
            public void failed(Throwable x)
            {
                long begin = httpStream.getNanoTime();
                super.failed(x);
                try
                {
                    onRequestComplete(NanoTime.since(begin));
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
     * Called back for each completed request with its execution's duration.
     * @param durationInNs the duration in nanoseconds of the completed request
     */
    protected abstract void onRequestComplete(long durationInNs);
}
