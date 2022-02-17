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

import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Processor;
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
    public void accept(Request request) throws Exception
    {
        // TODO more conditions than this
        // TODO handle other encodings
        // TODO more efficient than this
        HttpFields headers = request.getHeaders();
        if (!headers.contains(ACCEPT_GZIP) && !headers.contains(CONTENT_ENCODING_GZIP))
        {
            super.accept(request);
        }
        else
        {
            super.accept(new GzipRequest(request));
        }
    }

    private static class GzipRequest extends Request.Wrapper
    {
        private GzipRequest(Request delegate)
        {
            super(delegate);
        }

        @Override
        public void accept(Processor processor) throws Exception
        {
            getWrapped().accept((rq, rs, cb) ->
            {
                HttpFields newHeaders = HttpFields.from(rq.getHeaders(), f ->
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

                // TODO: override getHttpFields(), getContentLength(), etc.
                processor.process(this, new GzipResponse(rs), cb);
            });
        }
    }

    private static class GzipResponse extends Response.Wrapper
    {
        private GzipResponse(Response wrapped)
        {
            super(wrapped);
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            // TODO: deflate data.
            super.write(last, callback, content);
        }
    }
}
