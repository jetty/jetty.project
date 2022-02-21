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

package org.eclipse.jetty.core.server.handler;

import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;

public abstract class DelayedHandler extends Handler.Wrapper
{
    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        Handler handler = getHandler();
        if (handler == null)
            return null;
        Request.Processor processor = handler.handle(request);
        if (processor == null)
            return null;

        return delayed(request, processor);
    }

    protected abstract Request.Processor delayed(Request request, Request.Processor processor);

    public static class UntilContent extends DelayedHandler
    {
        @Override
        protected Request.Processor delayed(Request request, Request.Processor processor)
        {
            // TODO remove this setting from HttpConfig?
            if (!request.getHttpChannel().getHttpConfiguration().isDelayDispatchUntilContent())
                return processor;
            if (request.getContentLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
                return processor;

            return new DelayedProcessor(request, processor);
        }

        private static class DelayedProcessor implements Request.Processor, Runnable
        {
            private final Request.Processor _processor;
            private final Request _request;
            private Response _response;
            private Callback _callback;

            public DelayedProcessor(Request request, Request.Processor processor)
            {
                _request = request;
                _processor = processor;
            }

            @Override
            public void process(Request ignored, Response response, Callback callback) throws Exception
            {
                _response = response;
                _callback = callback;
                _request.demandContent(this);
            }

            @Override
            public void run()
            {
                try
                {
                    _processor.process(_request, _response, _callback);
                }
                catch (Throwable t)
                {
                    _response.writeError(_request, t, _callback);
                }
            }
        }
    }

    public static class QualityOfService extends DelayedHandler
    {
        private final int _maxPermits;
        private final Queue<QosProcessor> _queue = new ArrayDeque<>();
        private int _permits;

        public QualityOfService(int permits)
        {
            _maxPermits = permits;
        }

        @Override
        protected Request.Processor delayed(Request request, Request.Processor processor)
        {
            return new QosProcessor(request, processor);
        }

        private class QosProcessor implements Request.Processor, Callback
        {
            private final Request.Processor _processor;
            private final Request _request;
            private Response _response;
            private Callback _callback;

            private QosProcessor(Request request, Request.Processor processor)
            {
                _processor = processor;
                _request = request;
            }

            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                boolean accepted;
                synchronized (QualityOfService.this)
                {
                    _callback = callback;
                    accepted = _permits < _maxPermits;
                    if (accepted)
                        _permits++;
                    else
                    {
                        _response = response;
                        _queue.add(this);
                    }
                }
                if (accepted)
                    _processor.process(request, response, this);
            }

            @Override
            public void succeeded()
            {
                try
                {
                    _callback.succeeded();
                    release();
                }
                finally
                {
                    release();
                }
            }

            @Override
            public void failed(Throwable x)
            {
                try
                {
                    _callback.failed(x);
                    release();
                }
                finally
                {
                    release();
                }
            }

            private void release()
            {
                QosProcessor processor;
                synchronized (QualityOfService.this)
                {
                    processor = _queue.poll();
                    if (processor == null)
                        _permits--;
                }

                if (processor != null)
                {
                    try
                    {
                        processor._processor.process(processor._request, processor._response, processor);
                    }
                    catch (Throwable t)
                    {
                        processor._response.writeError(processor._request, t, processor);
                    }
                }
            }
        }
    }
}
