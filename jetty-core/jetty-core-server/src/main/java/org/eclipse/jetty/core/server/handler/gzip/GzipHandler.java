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

package org.eclipse.jetty.core.server.handler.gzip;

import java.nio.ByteBuffer;

import org.eclipse.jetty.core.server.Content;
import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;

public class GzipHandler extends Handler.Wrapper
{
    private static final HttpField ACCEPT_GZIP = new HttpField(HttpHeader.ACCEPT, "gzip");
    private static final HttpField CONTENT_ENCODING_GZIP = new HttpField(HttpHeader.CONTENT_ENCODING, "gzip");

    @Override
    public void offer(Request request, Acceptor acceptor) throws Exception
    {
        // TODO more conditions than this
        // TODO handle other encodings
        // TODO more efficient than this
        if (!request.getHeaders().contains(ACCEPT_GZIP) && !request.getHeaders().contains(CONTENT_ENCODING_GZIP))
        {
            super.offer(request, acceptor);
            return;
        }

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
        final Object inflaterAndOrDeflator = request.getHttpChannel().getAttribute("o.e.j.s.h.gzip.cachedCompression");

        super.offer(
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
            },
            (r, processor) ->
                acceptor.accept(r, exchange ->
                    processor.process(new Exchange.Wrapper(exchange)
                    {
                        @Override
                        public Response getResponse()
                        {
                            return new Response.Wrapper(exchange.getRequest(), super.getResponse())
                            {
                                @Override
                                public void write(boolean last, Callback callback, ByteBuffer... content)
                                {
                                    // TODO compress output
                                    super.write(last, callback, content);
                                }
                            };
                        }

                        @Override
                        public Content readContent()
                        {
                            // TODO inflate data
                            return super.readContent();
                        }

                        @Override
                        public void demandContent(Runnable onContentAvailable)
                        {
                            super.demandContent(onContentAvailable);
                        }
                    })));

    }
}
