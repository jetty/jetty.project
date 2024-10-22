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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Invocable;

public class DelayedHandler extends Handler.Wrapper
{
    public DelayedHandler()
    {
        this(null);
    }

    public DelayedHandler(Handler handler)
    {
        super(handler);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
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
            return next.handle(request, response, callback);

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
            case MULTIPART_FORM_DATA ->
            {
                if (request.getContext().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                    yield new UntilMultipartDelayedProcess(handler, request, response, callback, contentType, mpc);
                if (getServer().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                    yield new UntilMultipartDelayedProcess(handler, request, response, callback, contentType, mpc);
                yield null;
            }
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
                if (!getHandler().handle(getRequest(), getResponse(), getCallback()))
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
                getRequest().demand(Invocable.from(InvocationType.NON_BLOCKING, this::onContent));
            }
            else
            {
                RewindChunkRequest request = new RewindChunkRequest(getRequest(), chunk);
                try
                {
                    getHandler().handle(request, getResponse(), getCallback());
                }
                catch (Throwable x)
                {
                    // Use the wrapped request so that the error handling can
                    // consume the request content and release the already read chunk.
                    Response.writeError(request, getResponse(), getCallback(), x);
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
            FormFields.onFields(getRequest(), _charset, this::process, Invocable.from(InvocationType.NON_BLOCKING, this::executeProcess));
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

    protected static class UntilMultipartDelayedProcess extends DelayedProcess
    {
        private final String _contentType;
        private final MultiPartConfig _config;

        public UntilMultipartDelayedProcess(Handler handler, Request request, Response response, Callback callback, String contentType, MultiPartConfig config)
        {
            super(handler, request, response, callback);
            _contentType = contentType;
            _config = config;
        }

        @Override
        protected void delay()
        {
            Request request = getRequest();
            MultiPartFormData.onParts(request, request, _contentType, _config, this::process, Invocable.from(InvocationType.NON_BLOCKING, this::executeProcess));
        }

        private void process(MultiPartFormData.Parts fields, Throwable x)
        {
            if (x == null)
                super.process();
            else
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
        }

        private void executeProcess(MultiPartFormData.Parts fields, Throwable x)
        {
            if (x == null)
                // We must execute here as even though we have consumed all the input, we are probably
                // invoked in a demand runnable that is serialized with any write callbacks that might be done in process
                getRequest().getContext().execute(super::process);
            else
                Response.writeError(getRequest(), getResponse(), getCallback(), x);
        }
    }
}
