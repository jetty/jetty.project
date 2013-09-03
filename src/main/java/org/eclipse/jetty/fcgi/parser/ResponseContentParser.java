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
    public ResponseContentParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, FCGI.StreamType.STD_OUT, new ResponseListener(headerParser, listener));
    }

    private static class ResponseListener extends Parser.Listener.Adapter implements HttpParser.ResponseHandler<ByteBuffer>
    {
        private final HeaderParser headerParser;
        private final Parser.Listener listener;
        private final FCGIHttpParser httpParser;
        private State state = State.HEADERS;

        public ResponseListener(HeaderParser headerParser, Parser.Listener listener)
        {
            this.headerParser = headerParser;
            this.listener = listener;
            this.httpParser = new FCGIHttpParser(this);
        }

        @Override
        public void onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                switch (state)
                {
                    case HEADERS:
                    {
                        if (httpParser.parseHeaders(buffer))
                            state = State.CONTENT;
                        break;
                    }
                    case CONTENT:
                    {
                        if (httpParser.parseContent(buffer))
                            reset();
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        private void reset()
        {
            httpParser.reset();
            state = State.HEADERS;
        }

        @Override
        public void onEnd(int request)
        {
            // Never called for STD_OUT, since it relies on FCGI_END_REQUEST
            throw new IllegalStateException();
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
            // The HTTP request line does not exist in FCGI responses
            throw new IllegalStateException();
        }

        @Override
        public boolean parsedHeader(HttpField field)
        {
            try
            {
                if ("Status".equalsIgnoreCase(field.getName()))
                {
                    // Need to set the response status so the
                    // HttpParser can handle the content properly.
                    int code = Integer.parseInt(field.getValue().split(" ")[0]);
                    httpParser.setResponseStatus(code);
                }
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
            // Return from parsing so that we can parse the content
            return true;
        }

        @Override
        public boolean content(ByteBuffer buffer)
        {
            try
            {
                listener.onContent(headerParser.getRequest(), FCGI.StreamType.STD_OUT, buffer);
            }
            catch (Throwable x)
            {
                logger.debug("Exception while invoking listener " + listener, x);
            }
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            // Return from parsing so that we can parse the next headers
            return true;
        }

        @Override
        public void earlyEOF()
        {
            // TODO
        }

        @Override
        public void badMessage(int status, String reason)
        {
            // TODO
        }

        // Methods overridden to make them visible here
        private static class FCGIHttpParser extends HttpParser
        {
            private FCGIHttpParser(ResponseHandler<ByteBuffer> handler)
            {
                super(handler, 65 * 1024, true);
                reset();
            }

            @Override
            public void reset()
            {
                super.reset();
                setState(State.HEADER);
            }

            @Override
            protected boolean parseHeaders(ByteBuffer buffer)
            {
                return super.parseHeaders(buffer);
            }

            @Override
            protected boolean parseContent(ByteBuffer buffer)
            {
                return super.parseContent(buffer);
            }

            @Override
            protected void setResponseStatus(int status)
            {
                super.setResponseStatus(status);
            }
        }

        private enum State
        {
            HEADERS, CONTENT
        }
    }
}
