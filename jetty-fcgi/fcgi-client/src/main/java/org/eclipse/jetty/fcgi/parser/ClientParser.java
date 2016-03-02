//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.EnumMap;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;

public class ClientParser extends Parser
{
    private final EnumMap<FCGI.FrameType, ContentParser> contentParsers = new EnumMap<>(FCGI.FrameType.class);

    public ClientParser(Listener listener)
    {
        ResponseContentParser stdOutParser = new ResponseContentParser(headerParser, listener);
        contentParsers.put(FCGI.FrameType.STDOUT, stdOutParser);
        StreamContentParser stdErrParser = new StreamContentParser(headerParser, FCGI.StreamType.STD_ERR, listener);
        contentParsers.put(FCGI.FrameType.STDERR, stdErrParser);
        contentParsers.put(FCGI.FrameType.END_REQUEST, new EndRequestContentParser(headerParser, new EndRequestListener(listener, stdOutParser, stdErrParser)));
    }

    @Override
    protected ContentParser findContentParser(FCGI.FrameType frameType)
    {
        return contentParsers.get(frameType);
    }

    public interface Listener extends Parser.Listener
    {
        public void onBegin(int request, int code, String reason);

        public static class Adapter extends Parser.Listener.Adapter implements Listener
        {
            @Override
            public void onBegin(int request, int code, String reason)
            {
            }
        }
    }

    private class EndRequestListener implements Listener
    {
        private final Listener listener;
        private final StreamContentParser[] streamParsers;

        private EndRequestListener(Listener listener, StreamContentParser... streamParsers)
        {
            this.listener = listener;
            this.streamParsers = streamParsers;
        }

        @Override
        public void onBegin(int request, int code, String reason)
        {
            listener.onBegin(request, code, reason);
        }

        @Override
        public void onHeader(int request, HttpField field)
        {
            listener.onHeader(request, field);
        }

        @Override
        public void onHeaders(int request)
        {
            listener.onHeaders(request);
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            return listener.onContent(request, stream, buffer);
        }

        @Override
        public void onEnd(int request)
        {
            listener.onEnd(request);
            for (StreamContentParser streamParser : streamParsers)
                streamParser.end(request);
        }

        @Override
        public void onFailure(int request, Throwable failure)
        {
            listener.onFailure(request, failure);
            for (StreamContentParser streamParser : streamParsers)
                streamParser.end(request);
        }
    }
}
