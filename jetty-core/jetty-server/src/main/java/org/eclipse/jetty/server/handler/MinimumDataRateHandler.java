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

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * Checks that the wrapped handler can read/write at a minimal rate of N bytes per second.
 * When reading or writing does not conform to the specified rates, this handler prevents
 * further reads or writes by making them immediately fail.
 */
public class MinimumDataRateHandler extends StatisticsHandler
{
    private final long _minimumReadRate;
    private final long _minimumWriteRate;

    /**
     * Creates a {@code MinimumDataRateHandler} with the specified read and write rates.
     * @param minimumReadRate the minimum number of bytes to be read per second, or 0 for not checking the read rate.
     * @param minimumWriteRate the minimum number of bytes to be written per second, or 0 for not checking the write rate.
     */
    public MinimumDataRateHandler(long minimumReadRate, long minimumWriteRate)
    {
        this(null, minimumReadRate, minimumWriteRate);
    }

    /**
     * Creates a {@code MinimumDataRateHandler} with the specified read and write rates.
     *
     * @param handler the handler to wrap.
     * @param minimumReadRate the minimum number of bytes to be read per second, or 0 for not checking the read rate.
     * @param minimumWriteRate the minimum number of bytes to be written per second, or 0 for not checking the write rate.
     */
    public MinimumDataRateHandler(Handler handler, long minimumReadRate, long minimumWriteRate)
    {
        super(handler);
        _minimumReadRate = minimumReadRate;
        _minimumWriteRate = minimumWriteRate;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        MinimumDataRateRequest wrappedRequest = new MinimumDataRateRequest(request);
        MinimumDataRateResponse wrappedResponse = new MinimumDataRateResponse(wrappedRequest, response);
        return super.handle(wrappedRequest, wrappedResponse, callback);
    }

    private long dataRatePerSecond(long beginNanoTime, long bytes)
    {
        if (bytes == 0)
            return 0;
        long elapsed = NanoTime.since(beginNanoTime);
        return bytes * 1_000_000_000 / (elapsed > 0 ? elapsed : 1);
    }

    protected class MinimumDataRateRequest extends Request.Wrapper
    {
        private long _firstDemandNanoTime;
        private Content.Chunk _errorContent;

        private MinimumDataRateRequest(Request request)
        {
            super(request);
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (_minimumReadRate > 0)
            {
                if (_firstDemandNanoTime == 0)
                {
                    _firstDemandNanoTime = NanoTime.now();
                    if (_firstDemandNanoTime == 0)
                        _firstDemandNanoTime = 1;
                }

                long read = getBytesRead();
                if (read > 0)
                {
                    long rate = dataRatePerSecond(_firstDemandNanoTime, read);
                    if (rate < _minimumReadRate)
                    {
                        _errorContent = Content.Chunk.from(new TimeoutException("read rate is too low"));
                        demandCallback.run();
                        return;
                    }
                }
            }
            super.demand(demandCallback);
        }

        @Override
        public Content.Chunk read()
        {
            return _errorContent != null ? _errorContent : super.read();
        }
    }

    protected class MinimumDataRateResponse extends Response.Wrapper
    {
        private long _firstWriteNanoTime;
        
        public MinimumDataRateResponse(MinimumDataRateRequest request, Response wrapped)
        {
            super(request, wrapped);
        }

        @Override
        public MinimumDataRateRequest getRequest()
        {
            return (MinimumDataRateRequest)super.getRequest();
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (_minimumWriteRate > 0)
            {
                if (_firstWriteNanoTime == 0)
                {
                    _firstWriteNanoTime = NanoTime.now();
                    if (_firstWriteNanoTime == 0)
                        _firstWriteNanoTime = 1;
                }

                long written = getBytesWritten();
                if (written > 0)
                {
                    long rate = dataRatePerSecond(_firstWriteNanoTime, written);
                    if (rate < _minimumWriteRate)
                    {
                        fail(callback);
                        return;
                    }
                }
                else
                {
                    // It's the first write; if it is also the last, use a timer to verify the rate.
                    if (last)
                    {
                        long length = byteBuffer.remaining();
                        if (length > 0)
                        {
                            Callback original = callback;
                            long maxWriteDuration = length * 1_000_000_000L / _minimumWriteRate;
                            Scheduler.Task task = getRequest().getComponents().getScheduler().schedule(() -> fail(original), maxWriteDuration, TimeUnit.NANOSECONDS);
                            callback = Callback.from(task::cancel, callback);
                        }
                    }
                }
            }
            super.write(last, byteBuffer, callback);
        }

        private void fail(Callback callback)
        {
            TimeoutException cause = new TimeoutException("write rate is too low");
            getRequest()._errorContent = Content.Chunk.from(cause);
            callback.failed(cause);
        }
    }
}
