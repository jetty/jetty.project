//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;

public class ResponseContentParser extends StreamContentParser
{
    public ResponseContentParser(HeaderParser headerParser, FCGI.StreamType streamType, Parser.Listener listener)
    {
        super(headerParser, streamType, new ResponseListener(headerParser, listener));
    }

    private static class ResponseListener extends Parser.Listener.Adapter implements HttpParser.ResponseHandler<ByteBuffer>
    {
        private final HeaderParser headerParser;
        private final Parser.Listener listener;
        private final FCGIHttpParser httpParser;

        public ResponseListener(HeaderParser headerParser, Parser.Listener listener)
        {
            this.headerParser = headerParser;
            this.listener = listener;
            this.httpParser = new FCGIHttpParser(this);
        }

        @Override
        public void onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            httpParser.parseHeaders(buffer);
        }

        @Override
        public void onEnd(int request)
        {
            // TODO
            throw new UnsupportedOperationException();
        }

        @Override
        public int getHeaderCacheSize()
        {
            // TODO: configure this
            return 0;
        }

        @Override
        public boolean startResponse(HttpVersion version, int status, String reason)
        {
            throw new IllegalStateException();
        }

        @Override
        public boolean parsedHeader(HttpField field)
        {
            try
            {
                listener.onHeader(headerParser.getRequest(), field.getName(), field.getValue());
            }
            catch (Throwable x)
            {
                logger.debug("Exception while invoking listener " + listener, x);
            }
            return false;
        }

        @Override
        public boolean headerComplete()
        {
            try
            {
                listener.onHeaders(headerParser.getRequest());
            }
            catch (Throwable x)
            {
                logger.debug("Exception while invoking listener " + listener, x);
            }
            return false;
        }

        @Override
        public boolean content(ByteBuffer item)
        {
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            return false;
        }

        @Override
        public void earlyEOF()
        {
        }

        @Override
        public void badMessage(int status, String reason)
        {
        }
    }

    // Methods overridden to make them visible here
    private static class FCGIHttpParser extends HttpParser
    {
        private FCGIHttpParser(ResponseHandler<ByteBuffer> handler)
        {
            super(handler, 65 * 1024, true);
            setState(State.HEADER);
        }

        @Override
        protected boolean parseHeaders(ByteBuffer buffer)
        {
            return super.parseHeaders(buffer);
        }
    }
}
