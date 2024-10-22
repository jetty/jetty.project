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

package org.eclipse.jetty.fcgi.parser;

import java.io.EOFException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The parser for STDOUT frame content.</p>
 * <p>STDOUT frame content contain both the HTTP headers (but not the response line)
 * and the HTTP content (either Content-Length delimited or chunked).</p>
 * <p>For this reason, a special HTTP parser is used to parse the frames body.
 * This special HTTP parser is configured to skip the response line, and to
 * parse HTTP headers and HTTP content.</p>
 */
public class ResponseContentParser extends StreamContentParser
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseContentParser.class);

    private final ResponseParser parser;

    public ResponseContentParser(HeaderParser headerParser, ClientParser.Listener listener)
    {
        super(headerParser, FCGI.StreamType.STD_OUT, listener);
        this.parser = new ResponseParser(listener);
    }

    @Override
    public boolean noContent()
    {
        // Does nothing, since for responses the end of
        // content is signaled via a FCGI_END_REQUEST frame.
        return false;
    }

    @Override
    protected boolean onContent(ByteBuffer buffer)
    {
        return parser.parse(buffer);
    }

    @Override
    protected void end(int request)
    {
        super.end(request);
        parser.reset();
    }

    private class ResponseParser implements HttpParser.ResponseHandler
    {
        private final HttpFields.Mutable fields = HttpFields.build();
        private final ClientParser.Listener listener;
        private final FCGIHttpParser httpParser;
        private State state = State.HEADERS;
        private boolean seenResponseCode;
        private boolean stalled;

        private ResponseParser(ClientParser.Listener listener)
        {
            this.listener = listener;
            this.httpParser = new FCGIHttpParser(this);
        }

        private void reset()
        {
            fields.clear();
            httpParser.reset();
            state = State.HEADERS;
            seenResponseCode = false;
            stalled = false;
        }

        public boolean parse(ByteBuffer buffer)
        {
            int remaining = buffer.remaining();
            while (remaining > 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Response {} {}, state {} {}", getRequest(), FCGI.StreamType.STD_OUT, state, BufferUtil.toDetailString(buffer));

                switch (state)
                {
                    case HEADERS ->
                    {
                        if (httpParser.parseNext(buffer))
                        {
                            state = State.CONTENT_MODE;
                            if (stalled)
                                return true;
                        }
                        remaining = buffer.remaining();
                    }
                    case CONTENT_MODE ->
                    {
                        // If we have no indication of the content, then
                        // the HTTP parser will assume there is no content
                        // and will not parse it even if it is provided,
                        // so we have to parse it raw ourselves here.
                        boolean rawContent = fields.size() == 0 ||
                                             (fields.get(HttpHeader.CONTENT_LENGTH) == null &&
                                              fields.get(HttpHeader.TRANSFER_ENCODING) == null);
                        state = rawContent ? State.RAW_CONTENT : State.HTTP_CONTENT;
                    }
                    case RAW_CONTENT ->
                    {
                        ByteBuffer content = buffer.asReadOnlyBuffer();
                        buffer.position(buffer.limit());
                        if (notifyContent(content))
                            return true;
                        remaining = 0;
                    }
                    case HTTP_CONTENT ->
                    {
                        if (httpParser.parseNext(buffer))
                            return true;
                        remaining = buffer.remaining();
                    }
                    default -> throw new IllegalStateException();
                }
            }
            return false;
        }

        @Override
        public void startResponse(HttpVersion version, int status, String reason)
        {
            // The HTTP request line does not exist in FCGI responses
            throw new IllegalStateException();
        }

        @Override
        public void parsedHeader(HttpField httpField)
        {
            try
            {
                String name = httpField.getName();
                if ("Status".equalsIgnoreCase(name))
                {
                    if (!seenResponseCode)
                    {
                        seenResponseCode = true;

                        // Need to set the response status so the
                        // HttpParser can handle the content properly.
                        String value = httpField.getValue();
                        String[] parts = value.split(" ");
                        String status = parts[0];
                        int code = Integer.parseInt(status);
                        httpParser.setResponseStatus(code);
                        String reason = parts.length > 1 ? value.substring(status.length()) : HttpStatus.getMessage(code);

                        notifyBegin(code, reason.trim());
                        notifyHeaders(fields);
                    }
                }
                else
                {
                    fields.add(httpField);
                    if (seenResponseCode)
                        notifyHeader(httpField);
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while invoking listener {}", listener, x);
            }
        }

        private void notifyBegin(int code, String reason)
        {
            try
            {
                listener.onBegin(getRequest(), code, reason);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while invoking listener {}", listener, x);
            }
        }

        private void notifyHeader(HttpField httpField)
        {
            try
            {
                listener.onHeader(getRequest(), httpField);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while invoking listener {}", listener, x);
            }
        }

        private void notifyHeaders(HttpFields fields)
        {
            if (fields != null)
            {
                for (HttpField field : fields)
                {
                    notifyHeader(field);
                }
            }
        }

        private boolean notifyHeaders()
        {
            try
            {
                return listener.onHeaders(getRequest());
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while invoking listener {}", listener, x);
                return false;
            }
        }

        @Override
        public boolean headerComplete()
        {
            if (!seenResponseCode)
            {
                // No Status header but we have other headers, assume 200 OK.
                notifyBegin(200, "OK");
                notifyHeaders(fields);
            }
            // Remember whether we have demand.
            stalled = notifyHeaders();
            // Always return from HTTP parsing so that we
            // can parse the content with the FCGI parser.
            return true;
        }

        @Override
        public boolean content(ByteBuffer buffer)
        {
            return notifyContent(buffer);
        }

        private boolean notifyContent(ByteBuffer buffer)
        {
            try
            {
                return listener.onContent(getRequest(), FCGI.StreamType.STD_OUT, buffer);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while invoking listener {}", listener, x);
                return false;
            }
        }

        @Override
        public boolean contentComplete()
        {
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            // No need to notify the end of the response to the
            // listener because it will be done by FCGI_END_REQUEST.
            return false;
        }

        @Override
        public void earlyEOF()
        {
            fail(new EOFException());
        }

        @Override
        public void badMessage(HttpException failure)
        {
            fail((Throwable)failure);
        }

        protected void fail(Throwable failure)
        {
            try
            {
                listener.onFailure(getRequest(), failure);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while invoking listener {}", listener, x);
            }
        }
    }

    // Methods overridden to make them visible here
    private static class FCGIHttpParser extends HttpParser
    {
        private FCGIHttpParser(ResponseHandler handler)
        {
            super(handler, 65 * 1024, HttpCompliance.RFC7230);
            reset();
        }

        @Override
        public void reset()
        {
            super.reset();
            setResponseStatus(200);
            setState(State.HEADER);
        }

        @Override
        protected void setResponseStatus(int status)
        {
            super.setResponseStatus(status);
        }
    }

    private enum State
    {
        HEADERS, CONTENT_MODE, RAW_CONTENT, HTTP_CONTENT
    }
}
