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

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

public class StatisticsHandler extends Handler.Wrapper
{
    private final ConcurrentHashMap<String, Object> _connectionStats = new ConcurrentHashMap<>();

    private final CounterStatistic _requestStats = new CounterStatistic();
    private final SampleStatistic _requestTimeStats = new SampleStatistic();
    private final SampleStatistic _handleTimeStats = new SampleStatistic();

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        Object connectionStats = _connectionStats.computeIfAbsent(request.getConnectionMetaData().getId(), id ->
        {
            request.getHttpChannel().addConnectionCloseListener(x ->
            {
                // complete connections stats
                _connectionStats.remove(request.getConnectionMetaData().getId());
            });
            return "SomeConnectionStatsObject";
        });

        final LongAdder bytesRead = new LongAdder();
        final LongAdder bytesWritten = new LongAdder();

        _requestStats.increment();
        request.getHttpChannel().addStreamWrapper(s -> new HttpStream.Wrapper(s)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                if (response != null)
                {
                    // TODO status stats collected here.
                }

                for (ByteBuffer b : content)
                {
                    bytesWritten.add(b.remaining());
                }

                super.send(response, last, callback, content);
            }

            @Override
            public Content readContent()
            {
                Content content =  super.readContent();
                bytesRead.add(content.remaining());
                return content;
            }

            @Override
            public void succeeded()
            {
                _requestStats.decrement();
                _requestTimeStats.record(System.currentTimeMillis() - getNanoTimeStamp());
                super.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                _requestStats.decrement();
                _requestTimeStats.record(System.nanoTime() - getNanoTimeStamp());
                super.failed(x);
            }
        });

        StatisticsRequest statisticsRequest = new StatisticsRequest(request, bytesRead, bytesWritten);
        return statisticsRequest.wrapProcessor(super.handle(statisticsRequest));
    }

    private class StatisticsRequest extends Request.WrapperProcessor implements Callback
    {
        private final LongAdder _bytesRead;
        private final LongAdder _bytesWritten;
        private Callback _callback;

        private StatisticsRequest(Request request, LongAdder bytesRead, LongAdder bytesWritten)
        {
            super(request);
            _bytesRead = bytesRead;
            _bytesWritten = bytesWritten;
        }

        // TODO make this wrapper optional. Only needed if requestLog asks for these attributes.
        @Override
        public Object getAttribute(String name)
        {
            // return hidden attributes for requestLog
            return switch (name)
            {
                case "o.e.j.s.h.StatsHandler.bytesRead" -> _bytesRead;
                case "o.e.j.s.h.StatsHandler.bytesWritten" -> _bytesWritten;
                default -> super.getAttribute(name);
            };
        }

        @Override
        public void process(Request ignored, Response response, Callback callback) throws Exception
        {
            _callback = callback;
            super.process(this, response, this);
        }

        @Override
        public void succeeded()
        {
            try
            {
                _callback.succeeded();
            }
            finally
            {
                complete();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            try
            {
                _callback.failed(x);
            }
            finally
            {
                complete();
            }
        }

        private void complete()
        {
            _handleTimeStats.record(System.nanoTime() - getHttpChannel().getHttpStream().getNanoTimeStamp());
        }
    }
}
