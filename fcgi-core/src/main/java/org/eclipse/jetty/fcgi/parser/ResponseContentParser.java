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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ResponseContentParser extends StreamContentParser
{
    private static final Logger LOG = Log.getLogger(ResponseContentParser.class);

    public ResponseContentParser(HeaderParser headerParser, ClientParser.Listener listener)
    {
        super(headerParser, FCGI.StreamType.STD_OUT, new ResponseListener(headerParser, listener));
    }

    private static class ResponseListener extends Parser.Listener.Adapter implements HttpParser.ResponseHandler<ByteBuffer>
    {
        private final HeaderParser headerParser;
        private final ClientParser.Listener listener;
        private final FCGIHttpParser httpParser;
        private State state = State.HEADERS;
        private boolean begun;
        private List<HttpField> fields;

        public ResponseListener(HeaderParser headerParser, ClientParser.Listener listener)
        {
            this.headerParser = headerParser;
            this.listener = listener;
            this.httpParser = new FCGIHttpParser(this);
        }

        @Override
        public void onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            LOG.debug("Request {} {} content {} {}", request, stream, state, buffer);

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
            begun = false;
            fields = null;
        }

        @Override
        public void onEnd(int request)
        {
            // We are a STD_OUT stream so the end of the request is
            // signaled by a END_REQUEST. Here we just reset the state.
            reset();
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
        public boolean parsedHeader(HttpField httpField)
        {
            try
            {
                if ("Status".equalsIgnoreCase(httpField.getName()))
                {
                    if (!begun)
                    {
                        begun = true;

                        // Need to set the response status so the
                        // HttpParser can handle the content properly.
                        String[] parts = httpField.getValue().split(" ");
                        int code = Integer.parseInt(parts[0]);
                        httpParser.setResponseStatus(code);

                        String reason = parts.length > 1 ? parts[1] : HttpStatus.getMessage(code);
                        listener.onBegin(headerParser.getRequest(), code, reason);

                        if (fields != null)
                        {
                            for (HttpField field : fields)
                                listener.onHeader(headerParser.getRequest(), field);
                            fields = null;
                        }
                    }
                }
                else
                {
                    if (begun)
                    {
                        listener.onHeader(headerParser.getRequest(), httpField);
                    }
                    else
                    {
                        if (fields == null)
                            fields = new ArrayList<>();
                        fields.add(httpField);
                    }
                }
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
                if (begun)
                {
                    listener.onHeaders(headerParser.getRequest());
                }
                else
                {
                    fields = null;
                    // TODO: what here ?
                }
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
