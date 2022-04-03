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

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.BiConsumer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.FutureFormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DelayedHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(DelayedHandler.class);

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        Request.Processor processor = super.handle(request);
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
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return processor;
            if (request.getContentLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
                return processor;

            return new UntilContentProcessor(request, processor);
        }
    }

    private static class UntilContentProcessor implements Request.Processor, Runnable
    {
        private final Request.Processor _processor;
        private final Request _request;
        private Response _response;
        private Callback _callback;

        public UntilContentProcessor(Request request, Request.Processor processor)
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
                Response.writeError(_request, _response, _callback, t);
            }
        }
    }

    public static class UntilFormFields extends DelayedHandler
    {
        @Override
        protected Request.Processor delayed(Request request, Request.Processor processor)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return processor;

            Charset charset = FutureFormFields.getFormEncodedCharset(request);
            if (charset == null)
                return processor;

            return new UntilFormFieldsProcessor(request, processor, charset);
        }
    }

    private static class UntilFormFieldsProcessor implements Request.Processor, BiConsumer<Fields, Throwable>
    {
        private final Request _request;
        private final Request.Processor _processor;
        private final Charset _charset;
        private Response _response;
        private Callback _callback;

        public UntilFormFieldsProcessor(Request request, Request.Processor processor, Charset charset)
        {
            _processor = processor;
            _request = request;
            _charset = charset;
        }

        @Override
        public void process(Request ignored, Response response, Callback callback) throws Exception
        {
            _response = response;
            _callback = callback;

            // TODO get the max sizes
            FutureFormFields futureFormFields = new FutureFormFields(_request, _charset, -1, -1);
            _request.setAttribute(FutureFormFields.class.getName(), futureFormFields);

            if (futureFormFields.isDone())
                _processor.process(_request, response, callback);
            else
                futureFormFields.whenComplete(this);
        }

        @Override
        public void accept(Fields fields, Throwable throwable)
        {
            try
            {
                _processor.process(_request, _response, _callback);
            }
            catch (Throwable t)
            {
                Response.writeError(_request, _response, _callback, t);
            }
        }
    }

    public static class QualityOfService extends DelayedHandler
    {
        private final int _maxPermits;
        private final Queue<QualityOfServiceProcessor> _queue = new ArrayDeque<>();
        private int _permits;

        public QualityOfService(int permits)
        {
            _maxPermits = permits;
        }

        @Override
        protected Request.Processor delayed(Request request, Request.Processor processor)
        {
            return new QualityOfServiceProcessor(request, processor);
        }

        private class QualityOfServiceProcessor implements Request.Processor, Callback
        {
            private final Request.Processor _processor;
            private final Request _request;
            private Response _response;
            private Callback _callback;

            private QualityOfServiceProcessor(Request request, Request.Processor processor)
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
                QualityOfServiceProcessor processor;
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
                        Response.writeError(processor._request, processor._response, processor, t);
                    }
                }
            }
        }
    }
}
