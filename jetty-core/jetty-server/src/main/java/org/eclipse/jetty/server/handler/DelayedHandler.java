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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

public abstract class DelayedHandler extends Handler.Wrapper
{
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
            if (request.getLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
                return processor;
            // TODO: add logic to not delay if it's a CONNECT request.
            // TODO: also add logic to not delay if it's a request that expects 100 Continue.

            return new UntilContentProcessor(processor);
        }
    }

    private static class UntilContentProcessor implements Request.Processor
    {
        private final Request.Processor _processor;

        public UntilContentProcessor(Request.Processor processor)
        {
            _processor = processor;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            request.demand(() ->
            {
                try
                {
                    _processor.process(request, response, callback);
                }
                catch (Throwable t)
                {
                    Response.writeError(request, response, callback, t);
                }
            });
        }
    }

    public static class UntilFormFields extends DelayedHandler
    {
        @Override
        protected Request.Processor delayed(Request request, Request.Processor processor)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return processor;

            Charset charset = FormFields.getFormEncodedCharset(request);
            if (charset == null)
                return processor;

            return new UntilFormFieldsProcessor(processor);
        }
    }

    private static class UntilFormFieldsProcessor implements Request.Processor, BiConsumer<Fields, Throwable>
    {
        private final Request.Processor _processor;
        private Request _request;
        private Response _response;
        private Callback _callback;

        public UntilFormFieldsProcessor(Request.Processor processor)
        {
            _processor = processor;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            _request = request;
            _response = response;
            _callback = callback;
            FormFields.from(_request).whenComplete(this);
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

    public static class UntilMultiPartFormData extends DelayedHandler
    {
        @Override
        protected Request.Processor delayed(Request request, Request.Processor processor)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return processor;
            return new DelayedMultiPartProcessor(processor);
        }

        private static class DelayedMultiPartProcessor implements Request.Processor
        {
            private final Request.Processor _processor;

            private DelayedMultiPartProcessor(Request.Processor processor)
            {
                _processor = processor;
            }

            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                if (contentType != null)
                {
                    String contentTypeValue = HttpField.valueParameters(contentType, null);
                    if (MimeTypes.Type.MULTIPART_FORM_DATA.is(contentTypeValue))
                    {
                        String boundary = MultiPart.extractBoundary(contentType);
                        if (boundary != null)
                        {
                            WaitForFormData formData = new WaitForFormData(_processor, request, response, callback, boundary);
                            request.setAttribute(MultiPartFormData.class.getName(), formData);
                            formData.run();

                            if (formData.isDone())
                                _processor.process(request, response, callback);
                            else
                                formData.whenComplete(formData);
                            return;
                        }
                    }
                }

                _processor.process(request, response, callback);
            }
        }

        private static class WaitForFormData extends MultiPartFormData implements Runnable, BiConsumer<MultiPartFormData.Parts, Throwable>
        {
            private final Request.Processor _processor;
            private final Request _request;
            private final Response _response;
            private final Callback _callback;

            public WaitForFormData(Request.Processor processor, Request request, Response response, Callback callback, String boundary)
            {
                super(boundary);
                _processor = processor;
                _request = request;
                _response = response;
                _callback = callback;
            }

            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = _request.read();
                    if (chunk == null)
                    {
                        _request.demand(this);
                        return;
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        completeExceptionally(error.getCause());
                        return;
                    }
                    parse(chunk);
                    chunk.release();
                    if (chunk.isLast())
                        return;
                }
            }

            @Override
            public void accept(Parts parts, Throwable throwable)
            {
                try
                {
                    _processor.process(_request, _response, _callback);
                }
                catch (Throwable x)
                {
                    Response.writeError(_request, _response, _callback, x);
                }
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
