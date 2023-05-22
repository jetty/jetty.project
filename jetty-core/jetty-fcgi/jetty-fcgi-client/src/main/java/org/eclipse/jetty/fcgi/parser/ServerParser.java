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

import java.util.EnumMap;

import org.eclipse.jetty.fcgi.FCGI;

public class ServerParser extends Parser
{
    private final EnumMap<FCGI.FrameType, ContentParser> contentParsers = new EnumMap<>(FCGI.FrameType.class);

    public ServerParser(Listener listener)
    {
        super(listener);
        contentParsers.put(FCGI.FrameType.BEGIN_REQUEST, new BeginRequestContentParser(headerParser, listener));
        contentParsers.put(FCGI.FrameType.PARAMS, new ParamsContentParser(headerParser, listener));
        contentParsers.put(FCGI.FrameType.STDIN, new StreamContentParser(headerParser, FCGI.StreamType.STD_IN, listener));
    }

    @Override
    protected ContentParser findContentParser(FCGI.FrameType frameType)
    {
        ContentParser contentParser = contentParsers.get(frameType);
        if (contentParser == null)
            throw new IllegalArgumentException("unsupported frame type " + frameType);
        return contentParser;
    }

    public interface Listener extends Parser.Listener
    {
        public default void onStart(int request, FCGI.Role role, int flags)
        {
        }
    }
}
