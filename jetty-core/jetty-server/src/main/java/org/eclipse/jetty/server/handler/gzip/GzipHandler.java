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

package org.eclipse.jetty.server.handler.gzip;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class GzipHandler extends Handler.Wrapper
{
    private static final HttpField ACCEPT_GZIP = new HttpField(HttpHeader.ACCEPT, "gzip");
    private static final HttpField CONTENT_ENCODING_GZIP = new HttpField(HttpHeader.CONTENT_ENCODING, "gzip");

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        // TODO more conditions than this
        // TODO handle other encodings
        // TODO more efficient than this
        HttpFields headers = request.getHeaders();
        if (!headers.contains(ACCEPT_GZIP) && !headers.contains(CONTENT_ENCODING_GZIP))
            return super.handle(request);

        GzipRequest gzipRequest = new GzipRequest(request);
        return gzipRequest.wrapProcessor(super.handle(gzipRequest));
    }

    private static class GzipRequest extends Request.WrapperProcessor
    {
        private GzipRequest(Request delegate)
        {
            super(delegate);
        }

        @Override
        public HttpFields getHeaders()
        {
            // TODO only if we are gzipping and cache this
            return HttpFields.from(super.getHeaders(), f ->
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
        }

        @Override
        public void process(Request ignored, Response response, Callback callback) throws Exception
        {
            // TODO
            super.process(this, new GzipResponse(response, null, null, getHttpChannel().getHttpConfiguration().getOutputBufferSize(), false), callback);
        }
    }

}
