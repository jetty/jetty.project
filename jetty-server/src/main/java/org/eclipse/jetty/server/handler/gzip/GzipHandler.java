//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class GzipHandler extends Handler.Wrapper
{
    private static final HttpField ACCEPT_GZIP = new HttpField(HttpHeader.ACCEPT, "gzip");
    private static final HttpField CONTENT_ENCODING_GZIP = new HttpField(HttpHeader.CONTENT_ENCODING, "gzip");

    @Override
    public boolean handle(Request request, Response response) throws Exception
    {
        // TODO more conditions than this
        // TODO handle other encodings
        // TODO more efficient than this
        if (!request.getHeaders().contains(ACCEPT_GZIP) && !request.getHeaders().contains(CONTENT_ENCODING_GZIP))
            return super.handle(request, response);

        HttpFields updated = HttpFields.from(request.getHeaders(), f ->
        {
            if (f.getHeader() != null)
            {
                // TODO this is too simple
                if (CONTENT_ENCODING_GZIP.equals(f))
                    return null;
                if (f.getHeader().equals(HttpHeader.CONTENT_LENGTH))
                    return null;
            }
            return f;
        });

        // TODO look up cached or pool inflaters / deflated
        final Object inflaterAndOrDeflator = request.getChannel().getAttribute("o.e.j.s.h.gzip.cachedCompression");

        return super.handle(
            new Request.Wrapper(request)
            {
                @Override
                public HttpFields getHeaders()
                {
                    return updated;
                }

                @Override
                public long getContentLength()
                {
                    // TODO hide the content length
                    return -1;
                }

                @Override
                public Content readContent()
                {
                    // TODO inflate data
                    return super.readContent();
                }
            },
            new Response.Wrapper(response)
            {
                @Override
                public void write(boolean last, Callback callback, ByteBuffer... content)
                {
                    // TODO deflate data
                    super.write(last, callback, content);
                }
            });
    }
}
