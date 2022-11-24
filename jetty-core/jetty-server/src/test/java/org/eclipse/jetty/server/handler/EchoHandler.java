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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class EchoHandler extends Handler.Processor.NonBlocking
{
    @Override
    public void doProcess(Request request, Response response, Callback callback)
    {
        response.setStatus(200);
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (StringUtil.isNotBlank(contentType))
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);

        if (request.getHeaders().contains(HttpHeader.TRAILER))
        {
            HttpFields.Mutable responseTrailers = HttpFields.build();
            response.setTrailersSupplier(() -> responseTrailers);
        }

        long contentLength = request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
        if (contentLength >= 0)
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, contentLength);

        if (contentLength > 0 || contentLength == -1 && request.getHeaders().contains(HttpHeader.TRANSFER_ENCODING))
            Content.copy(request, response, response::writeTrailers, callback);
        else
            callback.succeeded();
    }
}
