//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.EnumMap;

import org.eclipse.jetty.fcgi.FCGI;

public class ServerParser extends Parser
{
    private final EnumMap<FCGI.FrameType, ContentParser> contentParsers = new EnumMap<>(FCGI.FrameType.class);

    public ServerParser(Listener listener)
    {
        contentParsers.put(FCGI.FrameType.BEGIN_REQUEST, new BeginRequestContentParser(headerParser, listener));
        contentParsers.put(FCGI.FrameType.PARAMS, new ParamsContentParser(headerParser, listener));
        contentParsers.put(FCGI.FrameType.STDIN, new StreamContentParser(headerParser, FCGI.StreamType.STD_IN, listener));
    }

    @Override
    protected ContentParser findContentParser(FCGI.FrameType frameType)
    {
        return contentParsers.get(frameType);
    }

    public interface Listener extends Parser.Listener
    {
        void onStart(int request, FCGI.Role role, int flags);

        class Adapter extends Parser.Listener.Adapter implements Listener
        {
            @Override
            public void onStart(int request, FCGI.Role role, int flags)
            {
            }
        }
    }
}
