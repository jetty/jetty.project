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

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;

/**
 * Handler to eagerly and asynchronously read and parse {@link MimeTypes.Type#FORM_ENCODED} and
 * {@link MimeTypes.Type#MULTIPART_FORM_DATA} content prior to invoking the {@link ServletHandler},
 * which can then consume them with blocking APIs but without blocking.
 */
public class EagerFormHandler extends Handler.Wrapper
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
        Request.Handler handler = getHandler();
        InvocationType invocationType = handler.getInvocationType();
        AtomicInteger done = new AtomicInteger(2);
        var onFields = new Promise.Invocable<Fields>()
        {
            @Override
            public void failed(Throwable x)
            {
                succeeded(null);
            }

            @Override
            public void succeeded(Fields result)
            {
                if (done.decrementAndGet() == 0)
                    invocationType.runWithoutBlocking(this::handle, request.getContext());
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            void handle()
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

        FormFields.onFields(request, onFields);
        if (done.decrementAndGet() == 0)
            onFields.handle();

        return true;
    }

    protected boolean handleMultiPartFormData(Request request, String contentType, org.eclipse.jetty.server.Response response, Callback callback)
    {
        Request.Handler handler = getHandler();
        InvocationType invocationType = handler.getInvocationType();
        AtomicInteger done = new AtomicInteger(2);
        var onParts = new Promise.Invocable<ServletMultiPartFormData.Parts>()
        {
            @Override
            public void failed(Throwable x)
            {
                succeeded(null);
            }

            @Override
            public void succeeded(ServletMultiPartFormData.Parts result)
            {
                if (done.decrementAndGet() == 0)
                    invocationType.runWithoutBlocking(this::handle, request.getContext());
            }

            void handle()
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

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }
        };

        ServletMultiPartFormData.onParts(Request.asInContext(request, ServletContextRequest.class).getServletApiRequest(), contentType, onParts);
        if (done.decrementAndGet() == 0)
            onParts.handle();
        return true;
    }
}
