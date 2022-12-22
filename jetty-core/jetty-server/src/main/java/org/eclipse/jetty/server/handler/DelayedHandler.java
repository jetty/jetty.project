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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;

public class DelayedHandler extends Handler.Wrapper
{
    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        boolean contentExpected = false;
        String contentType = null;
        loop: for (HttpField field : request.getHeaders())
        {
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_TYPE:
                    contentType = field.getValue();
                    break;

                case CONTENT_LENGTH:
                    contentExpected = field.getLongValue() > 0;
                    break;

                case TRANSFER_ENCODING:
                    contentExpected = field.contains(HttpHeaderValue.CHUNKED.asString());
                    break;

                case EXPECT:
                    if (field.contains(HttpHeaderValue.CONTINUE.asString()))
                    {
                        contentExpected = false;
                        break loop;
                    }
                    break;
                default:
                    break;
            }
        }

        MimeTypes.Type mimeType = MimeTypes.getBaseType(contentType);
        DelayedProcess delayed = newDelayedProcess(contentExpected, contentType, mimeType, next, request, response, callback);
        if (delayed == null)
            return next.process(request, response, callback);

        delayed.delay();
        return true;
    }

    protected DelayedProcess newDelayedProcess(boolean contentExpected, String contentType, MimeTypes.Type mimeType, Handler handler, Request request, Response response, Callback callback)
    {
        // if no content is expected, then no delay
        if (!contentExpected)
            return null;

        // if we are not configured to delay dispatch, then no delay
        if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
            return null;

        // If there is no known content type, then delay only until content is available
        if (mimeType == null)
            return new UntilContentDelayedProcess(handler, request, response, callback);

        // Otherwise, delay until a known content type is fully read; or if the type is not known then until the content is available
        return switch (mimeType)
        {
            case FORM_ENCODED -> new UntilFormDelayedProcess(handler, request, response, callback, contentType);
            case MULTIPART_FORM_DATA -> new UntilMultiPartDelayedProcess(handler, request, response, callback, contentType);
            default -> new UntilContentDelayedProcess(handler, request, response, callback);
        };
    }

    protected abstract static class DelayedProcess
    {
        private final Handler _handler;
        private final Request _request;
        private final Response _response;
        private final Callback _callback;

        protected DelayedProcess(Handler handler, Request request, Response response, Callback callback)
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

        protected void process()
        {
            try
            {
                if (!getHandler().process(getRequest(), getResponse(), getCallback()))
                    Response.writeError(getRequest(), getResponse(), getCallback(), HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                Response.writeError(getRequest(), getResponse(), getCallback(), t);
            }
        }

        protected abstract void delay() throws Exception;
    }

    protected static class UntilContentDelayedProcess extends DelayedProcess
    {
        public UntilContentDelayedProcess(Handler handler, Request request, Response response, Callback callback)
        {
            super(handler, request, response, callback);
        }

        @Override
        protected void delay()
        {
            Content.Chunk chunk = super.getRequest().read();
            if (chunk == null)
            {
                getRequest().demand(this::onContent);
            }
            else
            {
                try
                {
                    getHandler().process(new RewindRequest(getRequest(), chunk), getResponse(), getCallback());
                }
                catch (Exception e)
                {
                    Response.writeError(getRequest(), getResponse(), getCallback(), e);
                }
            }
        }

        public void onContent()
        {
            // We must execute here, because demand callbacks are serialized and process may block on a demand callback
            getRequest().getContext().execute(this::process);
        }

        static class RewindRequest extends Request.Wrapper
        {
            private final AtomicReference<Content.Chunk> _chunk;

            public RewindRequest(Request wrapped, Content.Chunk chunk)
            {
                super(wrapped);
                _chunk = new AtomicReference<>(chunk);
            }

            @Override
            public Content.Chunk read()
            {
                Content.Chunk chunk = _chunk.getAndSet(null);
                if (chunk != null)
                    return chunk;
                return super.read();
            }
        }
    }

    protected static class UntilFormDelayedProcess extends DelayedProcess
    {
        private final Charset _charset;

        public UntilFormDelayedProcess(Handler handler, Request wrapped, Response response, Callback callback, String contentType)
        {
            super(handler, wrapped, response, callback);

            String cs = MimeTypes.getCharsetFromContentType(contentType);
            _charset = StringUtil.isEmpty(cs) ? StandardCharsets.UTF_8 : Charset.forName(cs);
        }

        @Override
        protected void delay()
        {
            CompletableFuture<Fields> futureFormFields = FormFields.from(getRequest(), _charset);

            // if we are done already, then we are still in the scope of the original process call and can
            // accept directly, otherwise we must execute a call to process as we are within a serialized
            // demand callback.
            futureFormFields.whenComplete(futureFormFields.isDone() ? this::accept : this::acceptAndExecute);
        }

        public void accept(Fields fields, Throwable x)
        {
            if (x == null)
                super.process();
            else
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
        }

        public void acceptAndExecute(Fields fields, Throwable x)
        {
            if (x == null)
                // We must execute here as even though we have consumed all the input, we are probably
                // invoked in a demand runnable that is serialized with any write callbacks that might be done in process
                getRequest().getContext().execute(super::process);
            else
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
        }
    }

    protected static class UntilMultiPartDelayedProcess extends DelayedProcess
    {
        private final MultiPartFormData _formData;

        public UntilMultiPartDelayedProcess(Handler handler, Request wrapped, Response response, Callback callback, String contentType)
        {
            super(handler, wrapped, response, callback);
            String boundary = MultiPart.extractBoundary(contentType);
            _formData = boundary == null ? null : new MultiPartFormData(boundary);
        }

        public void accept(MultiPartFormData.Parts parts, Throwable x)
        {
            if (x == null)
            {
                getRequest().setAttribute(MultiPartFormData.class.getName(), _formData);
                super.process();
            }
            else
            {
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
            }
        }

        public void acceptAndExecute(MultiPartFormData.Parts parts, Throwable x)
        {
            if (x == null)
            {
                getRequest().setAttribute(MultiPartFormData.class.getName(), _formData);
                // We must execute here as even though we have consumed all the input, we are probably
                // invoked in a demand runnable that is serialized with any write callbacks that might be done in process
                getRequest().getContext().execute(super::process);
            }
            else
            {
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
            }
        }

        @Override
        public void delay()
        {
            if (_formData == null)
            {
                super.process();
            }
            else
            {
                Object baseTempDirectory = getRequest().getContext().getAttribute(Server.BASE_TEMP_DIR_ATTR);
                _formData.setFilesDirectory(IO.asFile(baseTempDirectory == null ? System.getProperty("java.io.tmpdir") : baseTempDirectory).toPath());
                readAndParse();
                // if we are done already, then we are still in the scope of the original process call and can
                // accept directly, otherwise we must execute a call to process as we are within a serialized
                // demand callback.
                _formData.whenComplete(_formData.isDone() ? this::accept : this::acceptAndExecute);
            }
        }

        private void readAndParse()
        {
            while (!_formData.isDone())
            {
                Content.Chunk chunk = getRequest().read();
                if (chunk == null)
                {
                    getRequest().demand(this::readAndParse);
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
                {
                    if (!_formData.isDone())
                        accept(null, new IOException("Incomplete multipart"));
                    return;
                }
            }
        }
    }
}
