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
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;

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

        // if no mimeType, then no delay
        if (mimeType == null)
            return null;

        // Otherwise, delay until a known content type is fully read; or if the type is not known then until the content is available
        return switch (mimeType)
        {
            case FORM_ENCODED -> new UntilFormDelayedProcess(handler, request, response, callback, contentType);
            case MULTIPART_FORM_DATA ->
            {
                MultiPartConfig config;
                if (request.getContext().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                    config = mpc;
                else if (getHandler().getServer().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                    config = mpc;
                else
                    yield null;

                yield new UntilMultipartDelayedProcess(handler, request, response, callback, contentType, config);
            }
            default -> null;
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

    protected static class UntilFormDelayedProcess extends DelayedProcess
    {
        private final Charset _charset;

        public UntilFormDelayedProcess(Handler handler, Request request, Response response, Callback callback, String contentType)
        {
            super(handler, request, response, callback);

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

            CompletableFuture<MultiPartFormData.Parts> futureMultiPart = MultiPartFormData.from(request, request, _contentType, _config);

            // if we are done already, then we can call process in this thread, otherwise
            // we must call executeProcess when the multipart is complete, since it will be called from a serialized callback.
            futureMultiPart.whenComplete(futureMultiPart.isDone() ? this::process : this::executeProcess);
        }

        private void process(MultiPartFormData.Parts parts, Throwable failure)
        {
            if (failure == null)
                super.process();
            else
                Response.writeError(getRequest(), getResponse(), getCallback(), failure);
        }

        private void executeProcess(MultiPartFormData.Parts parts, Throwable x)
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
