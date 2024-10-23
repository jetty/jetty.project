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

package org.eclipse.jetty.ee10.servlet;

import java.util.function.BiConsumer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.DelayedHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * Handler to eagerly and asynchronously read and parse {@link MimeTypes.Type#FORM_ENCODED} and
 * {@link MimeTypes.Type#MULTIPART_FORM_DATA} content prior to invoking the {@link ServletHandler},
 * which can then consume them with blocking APIs but without blocking.
 */
public class EagerFormHandler extends DelayedHandler
{
    public EagerFormHandler()
    {
        this(null);
    }

    public EagerFormHandler(Handler handler)
    {
        super(handler);
    }

    @Override
    public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
    {
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (contentType == null)
            return super.handle(request, response, callback);

        MimeTypes.Type mimeType = MimeTypes.getBaseType(contentType);
        if (mimeType == null)
            return super.handle(request, response, callback);

        return switch (mimeType)
        {
            case FORM_ENCODED -> handleFormFields(request, response, callback);
            case MULTIPART_FORM_DATA -> handleMultiPartFormData(request, contentType, response, callback);
            default -> super.handle(request, response, callback);
        };
    }

    protected boolean handleFormFields(Request request, org.eclipse.jetty.server.Response response, Callback callback)
    {
        Request.Handler handler = super::handle;
        Promise<Fields> onFields = new Promise<>()
        {
            @Override
            public void failed(Throwable x)
            {
                callback.failed(x);
            }

            @Override
            public void succeeded(Fields result)
            {
                try
                {
                    if (!handler.handle(request, response, callback))
                        callback.failed(new IllegalStateException("Not Handled"));
                }
                catch (Throwable t)
                {
                    callback.failed(t);
                }
            }
        };

        InvocablePromise<Fields> executeOnFields = Invocable.from(request.getContext(), onFields);
        FormFields.onFields(request, onFields, executeOnFields);
        return true;
    }

    protected boolean handleMultiPartFormData(Request request, String contentType, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
    {
        BiConsumer<ServletMultiPartFormData.Parts, Throwable> onParts = (fields, error) ->
        {
            try
            {
                if (!super.handle(request, response, callback))
                    callback.failed(new IllegalStateException("Not Handled"));
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        };

        InvocableBiConsumer<ServletMultiPartFormData.Parts, Throwable> executeOnParts = new InvocableBiConsumer<>()
        {
            @Override
            public void accept(ServletMultiPartFormData.Parts fields, Throwable error)
            {
                request.getContext().execute(() ->
                {
                    onParts.accept(fields, error);
                });
            }

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }
        };

        ServletMultiPartFormData.onParts(Request.as(request, ServletContextRequest.class).getServletApiRequest(), contentType, onParts, executeOnParts);
        return true;
    }
}
