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

import java.util.Objects;
import java.util.function.BiConsumer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
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
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        DelayedProcess delayed = newDelayedProcess(next, request, response, callback);
        if (delayed == null)
            return next.process(request, response, callback);

        delay(delayed);
        return true;
    }

    protected DelayedProcess newDelayedProcess(Handler next, Request request, Response response, Callback callback)
    {
        return new DelayedProcess(next, request, response, callback);
    }

    protected abstract void delay(DelayedProcess delay) throws Exception;

    protected static class DelayedProcess implements Runnable
    {
        private final Handler _handler;
        private final Request _request;
        private final Response _response;
        private final Callback _callback;

        public DelayedProcess(Handler handler, Request request, Response response, Callback callback)
        {
            _handler = Objects.requireNonNull(handler);
            _request = Objects.requireNonNull(request);
            _response = Objects.requireNonNull(response);
            _callback = Objects.requireNonNull(callback);
        }

        protected Handler getHandler()
        {
            return _handler;
        }

        protected Request getRequest()
        {
            return _request;
        }

        protected Response getResponse()
        {
            return _response;
        }

        protected Callback getCallback()
        {
            return _callback;
        }

        protected boolean process() throws Exception
        {
            return getHandler().process(getRequest(), getResponse(), getCallback());
        }

        @Override
        public void run()
        {
            try
            {
                if (!process())
                    Response.writeError(getRequest(), getResponse(), getCallback(), HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                Response.writeError(getRequest(), getResponse(), getCallback(), t);
            }
        }
    }

    public static class UntilContent extends DelayedHandler
    {
        @Override
        protected DelayedProcess newDelayedProcess(Handler next, Request request, Response response, Callback callback)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return null;

            if (request.getLength() == 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
                return null;

            // TODO: add logic to not delay if it's a CONNECT request.
            // TODO: also add logic to not delay if it's a request that expects 100 Continue.

            return new DelayedProcess(next, request, response, callback);
        }

        @Override
        protected void delay(DelayedProcess request)
        {
            request.getRequest().demand(request);
        }
    }

    public static class UntilFormFields extends DelayedHandler
    {
        @Override
        protected FormDelayedProcess newDelayedProcess(Handler next, Request request, Response response, Callback callback)
        {
            if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
                return null;
            if (FormFields.getFormEncodedCharset(request) == null)
                return null;

            return new FormDelayedProcess(next, request, response, callback);
        }

        @Override
        protected void delay(DelayedProcess delayed)
        {
            FormFields.from(delayed.getRequest()).whenComplete((FormDelayedProcess)delayed);
        }

        protected static class FormDelayedProcess extends DelayedProcess implements BiConsumer<Fields, Throwable>
        {
            public FormDelayedProcess(Handler handler, Request wrapped, Response response, Callback callback)
            {
                super(handler, wrapped, response, callback);
            }

            @Override
            public void accept(Fields fields, Throwable x)
            {
                if (x == null)
                    run();
                else
                    Response.writeError(getRequest(), getResponse(), getCallback(), x);
            }
        }
    }

    public static class UntilMultiPartFormData extends DelayedHandler
    {
        @Override
        protected MultiPartDelayedProcess newDelayedProcess(Handler next, Request request, Response response, Callback callback)
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

            return new MultiPartDelayedProcess(next, request, response, callback, boundary);
        }

        @Override
        protected void delay(DelayedProcess request)
        {
            request.run();
            ((MultiPartDelayedProcess)request).whenDone();
        }

        protected static class MultiPartDelayedProcess extends DelayedProcess implements BiConsumer<MultiPartFormData.Parts, Throwable>
        {
            private final MultiPartFormData _formData;

            public MultiPartDelayedProcess(Handler handler, Request wrapped, Response response, Callback callback, String boundary)
            {
                super(handler, wrapped, response, callback);
                _formData = new MultiPartFormData(boundary);
                getRequest().setAttribute(MultiPartFormData.class.getName(), _formData);
            }

            @Override
            public void accept(MultiPartFormData.Parts parts, Throwable x)
            {
                if (x == null)
                    super.run();
                else
                    Response.writeError(getRequest(), getResponse(), getCallback(), x);
            }

            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = getRequest().read();
                    if (chunk == null)
                    {
                        getRequest().demand(this);
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
}
