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

import java.util.concurrent.CompletableFuture;

import jakarta.servlet.ServletRequest;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

/**
 * Handler to eagerly and asynchronously read and parse {@link MimeTypes.Type#FORM_ENCODED} and
 * {@link MimeTypes.Type#MULTIPART_FORM_DATA} content prior to invoking the {@link ServletHandler},
 * which can then consume them with blocking APIs but without blocking.
 * @see FormFields#from(Request)
 * @see ServletMultiPartFormData#from(ServletRequest)
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

        CompletableFuture<?> future =  switch (mimeType)
        {
            case FORM_ENCODED -> FormFields.from(request);
            case MULTIPART_FORM_DATA -> ServletMultiPartFormData.from(Request.as(request, ServletContextRequest.class).getServletApiRequest(), contentType);
            default -> null;
        };

        if (future == null)
            return super.handle(request, response, callback);

        future.whenComplete((result, failure) ->
        {
            // The result and failure are not handled here. Rather we call the next handler
            // to allow the normal processing to handle the result or failure, which will be
            // provided via the attribute to ServletApiRequest#getParts()
            try
            {
                if (!super.handle(request, response, callback))
                    callback.failed(new IllegalStateException("Not Handled"));
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        });
        return true;
    }
}
