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

/**
 * <p>A Handler that delay's processing a request until some (or all) of it's content is available.</p>
 * <p>For requests without a body, this handler does nothing. For requests with a body, the content-type
 * is used to select the type delay.  For known contents that will either always be read into memory
 * ( e.g. FORM_ENCODED) or have a memory efficient representation (e.g. MULTIPART_FORM_DATA), the entire
 * content is asynchronously read before the request is processed. Otherwise, the processing is delayed
 * until the first chunk of content is available.</p>
 * <p>This handler should be used when an application frequently receives requests with content, on which
 * it blocks reading them. Using this handler can reduce or avoid blocking on the content, and thus reduce
 * thread usage.</p>
 */
public class DelayedContentHandler extends Handler.Wrapper
{
    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        // if we are not configured to delay dispatch, then no delay
        if (!request.getConnectionMetaData().getHttpConfiguration().isDelayDispatchUntilContent())
            return next.process(request, response, callback);

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

        if (!contentExpected)
            return next.process(request, response, callback);

        DelayedProcess delayed = newDelayedProcess(contentType, next, request, response, callback);
        if (delayed == null)
            return next.process(request, response, callback);

        delayed.delay();
        return true;
    }

    /** Create a {@link DelayedProcess} instance to delay a request.
     * @param contentType The content type for which to delay.
     * @param handler The handler that will ultimately process the request.
     * @param request The request.
     * @param response The response.
     * @param callback The Callback.
     * @return A DelayedProcess instance to delay the request, or null for no delay.
     */
    protected DelayedProcess newDelayedProcess(String contentType, Handler handler, Request request, Response response, Callback callback)
    {
        MimeTypes.Type mimeType = MimeTypes.getBaseType(contentType);

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
                    getHandler().process(new RewindChunkRequest(getRequest(), chunk), getResponse(), getCallback());
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

        private static class RewindChunkRequest extends Request.Wrapper
        {
            private final AtomicReference<Content.Chunk> _chunk;

            public RewindChunkRequest(Request wrapped, Content.Chunk chunk)
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
            // process directly, otherwise we must execute a call to process as we are within a serialized
            // demand callback.
            futureFormFields.whenComplete(futureFormFields.isDone() ? this::process : this::executeProcess);
        }

        private void process(Fields fields, Throwable x)
        {
            if (x == null)
                super.process();
            else
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
        }

        private void executeProcess(Fields fields, Throwable x)
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
            getRequest().setAttribute(MultiPartFormData.class.getName(), _formData);
        }

        private void process(MultiPartFormData.Parts parts, Throwable x)
        {
            if (x == null)
            {
                super.process();
            }
            else
            {
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
            }
        }

        private void executeProcess(MultiPartFormData.Parts parts, Throwable x)
        {
            if (x == null)
            {
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
                _formData.setFilesDirectory(getRequest().getContext().getTempDirectory().toPath());
                readAndParse();
                // if we are done already, then we are still in the scope of the original process call and can
                // process directly, otherwise we must execute a call to process as we are within a serialized
                // demand callback.
                _formData.whenComplete(_formData.isDone() ? this::process : this::executeProcess);
            }
        }

        private void readAndParse()
        {
            while (true)
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
                        process(null, new IOException("Incomplete multipart"));
                    return;
                }
            }
        }
    }
}
