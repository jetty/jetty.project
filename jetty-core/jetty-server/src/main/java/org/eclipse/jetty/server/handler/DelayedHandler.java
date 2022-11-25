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

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
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

public abstract class DelayedHandler<R extends DelayedHandler.DelayedRequest> extends Handler.Wrapper
{
    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return;

        R delayed = newDelayedRequest(next, request, response, callback);
        if (delayed == null)
            next.process(request, response, callback);
        else
        {
            request.accept();
            delay(delayed);
        }
    }

    protected abstract R newDelayedRequest(Handler next, Request request, Response response, Callback callback);

    protected abstract void delay(R request);

    protected static class DelayedRequest extends Request.Wrapper implements Runnable
    {
        private final Handler _handler;
        private final Response _response;
        private final Callback _callback;

        public DelayedRequest(Handler handler, Request wrapped, Response response, Callback callback)
        {
            super(wrapped);
            _handler = Objects.requireNonNull(handler);
            _response = Objects.requireNonNull(response);
            _callback = Objects.requireNonNull(callback);
        }

        protected Handler getHandler()
        {
            return _handler;
        }

        protected Response getResponse()
        {
            return _response;
        }

        protected Callback getCallback()
        {
            return _callback;
        }

        @Override
        public void run()
        {
            try
            {
                process();
            }
            catch (Throwable t)
            {
                Response.writeError(getWrapped(), getResponse(), getCallback(), t);
            }
        }

        protected void process() throws Exception
        {
            _handler.process(this, getResponse(), getCallback());
        }
    }

    protected static class AcceptingDelayedRequest extends DelayedRequest
    {
        private final AtomicBoolean _accepted = new AtomicBoolean();

        public AcceptingDelayedRequest(Handler handler, Request wrapped, Response response, Callback callback)
        {
            super(handler, wrapped, response, callback);
            wrapped.accept();
        }

        @Override
        public Content.Chunk read()
        {
            if (!isAccepted())
                throw new IllegalStateException("!accepted");
            return super.read();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (!isAccepted())
                throw new IllegalStateException("!accepted");
            super.demand(demandCallback);
        }

        @Override
        public void run()
        {
            try
            {
                process();
                if (!isAccepted())
                    Response.writeError(getWrapped(), getResponse(), getCallback(), 404);
            }
            catch (Throwable t)
            {
                Response.writeError(getWrapped(), getResponse(), getCallback(), t);
            }
        }

        @Override
        public void accept()
        {
            if (!_accepted.compareAndSet(false, true))
                throw new IllegalStateException("already accepted");
        }

        @Override
        public boolean isAccepted()
        {
            return _accepted.get();
        }
    }

    public static class UntilContent extends DelayedHandler<AcceptingDelayedRequest>
    {
        @Override
        protected AcceptingDelayedRequest newDelayedRequest(Handler next, Request request, Response response, Callback callback)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return null;

            if (request.getLength() == 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
                return null;

            return new AcceptingDelayedRequest(next, request, response, callback);
        }

        @Override
        protected void delay(AcceptingDelayedRequest request)
        {
            request.getWrapped().demand(request);
        }
    }

    public static class UntilFormFields extends DelayedHandler<UntilFormFields.FormDelayedRequest>
    {
        @Override
        protected FormDelayedRequest newDelayedRequest(Handler next, Request request, Response response, Callback callback)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return null;
            if (FormFields.getFormEncodedCharset(request) == null)
                return null;

            return new FormDelayedRequest(next, request, response, callback);
        }

        @Override
        protected void delay(FormDelayedRequest request)
        {
            FormFields.from(request.getWrapped()).whenComplete(request);
        }

        protected static class FormDelayedRequest extends DelayedRequest implements BiConsumer<Fields, Throwable>
        {
            public FormDelayedRequest(Handler handler, Request wrapped, Response response, Callback callback)
            {
                super(handler, wrapped, response, callback);
            }

            @Override
            public void accept(Fields fields, Throwable x)
            {
                if (x == null)
                    run();
                else
                    Response.writeError(getWrapped(), getResponse(), getCallback(), x);
            }
        }
    }

    public static class UntilMultiPartFormData extends DelayedHandler<UntilMultiPartFormData.MultiPartDelayedRequest>
    {
        @Override
        protected MultiPartDelayedRequest newDelayedRequest(Handler next, Request request, Response response, Callback callback)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return null;

            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            if (contentType == null)
                return null;

            String contentTypeValue = HttpField.valueParameters(contentType, null);
            if (!MimeTypes.Type.MULTIPART_FORM_DATA.is(contentTypeValue))
                return null;

            String boundary = MultiPart.extractBoundary(contentType);
            if (boundary == null)
                return null;

            return new MultiPartDelayedRequest(next, request, response, callback, boundary);
        }

        @Override
        protected void delay(MultiPartDelayedRequest request)
        {
            request.run();
            request.whenDone();
        }

        protected static class MultiPartDelayedRequest extends DelayedRequest implements BiConsumer<MultiPartFormData.Parts, Throwable>
        {
            private final MultiPartFormData _formData;

            public MultiPartDelayedRequest(Handler handler, Request wrapped, Response response, Callback callback, String boundary)
            {
                super(handler, wrapped, response, callback);
                _formData = new MultiPartFormData(boundary);
                setAttribute(MultiPartFormData.class.getName(), _formData);
            }

            @Override
            public void accept(MultiPartFormData.Parts parts, Throwable x)
            {
                if (x == null)
                    super.run();
                else
                    Response.writeError(getWrapped(), getResponse(), getCallback(), x);
            }

            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = getWrapped().read();
                    if (chunk == null)
                    {
                        getWrapped().demand(this);
                        return;
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        _formData.completeExceptionally(error.getCause());
                        return;
                    }
                    _formData.parse(chunk);
                    chunk.release();
                    if (chunk.isLast())
                        return;
                }
            }

            public void whenDone()
            {
                if (_formData.isDone())
                    super.run();
                else
                    _formData.whenComplete(this);
            }
        }
    }

    public static class QualityOfService extends DelayedHandler<QualityOfService.QoSDelayedRequest>
    {
        private final int _maxPermits;
        private final Queue<QoSDelayedRequest> _queue = new ArrayDeque<>();
        private int _permits;

        public QualityOfService(int permits)
        {
            _maxPermits = permits;
        }

        @Override
        protected QoSDelayedRequest newDelayedRequest(Handler next, Request request, Response response, Callback callback)
        {
            return new QoSDelayedRequest(next, request, response, callback);
        }

        @Override
        protected void delay(QoSDelayedRequest request)
        {
            boolean accepted;
            synchronized (QualityOfService.this)
            {
                accepted = _permits < _maxPermits;
                if (accepted)
                    _permits++;
                else
                    _queue.add(request);
            }
            if (accepted)
                request.run();
        }

        protected class QoSDelayedRequest extends DelayedRequest implements Callback
        {
            public QoSDelayedRequest(Handler handler, Request wrapped, Response response, Callback callback)
            {
                super(handler, wrapped, response, callback);
            }

            @Override
            protected Callback getCallback()
            {
                return this;
            }

            @Override
            protected void process() throws Exception
            {
                getHandler().process(this, getResponse(), this);
                if (!getWrapped().isAccepted())
                    release();
            }

            @Override
            public void succeeded()
            {
                try
                {
                    if (!isAccepted())
                        throw new IllegalStateException("!accepted");
                    getCallback().succeeded();
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
                    if (!isAccepted())
                        throw new IllegalStateException("!accepted");
                    getCallback().failed(x);
                }
                finally
                {
                    release();
                }
            }

            private void release()
            {
                QoSDelayedRequest request;
                synchronized (QualityOfService.this)
                {
                    request = _queue.poll();
                    if (request == null)
                        _permits--;
                }

                if (request != null)
                    request.run();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return getCallback().getInvocationType();
            }
        }
    }
}
