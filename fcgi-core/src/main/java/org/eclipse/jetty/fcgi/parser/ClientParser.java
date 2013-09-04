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

import java.util.EnumMap;

import org.eclipse.jetty.fcgi.FCGI;

public class ClientParser extends Parser
{
    private final EnumMap<FCGI.FrameType, ContentParser> contentParsers = new EnumMap<>(FCGI.FrameType.class);

    public ClientParser(Listener listener)
    {
        contentParsers.put(FCGI.FrameType.STDOUT, new ResponseContentParser(headerParser, listener));
        contentParsers.put(FCGI.FrameType.STDERR, new StreamContentParser(headerParser, FCGI.StreamType.STD_ERR, listener));
        contentParsers.put(FCGI.FrameType.END_REQUEST, new EndRequestContentParser(headerParser, listener));
    }

    @Override
    protected ContentParser findContentParser(FCGI.FrameType frameType)
    {
        return contentParsers.get(frameType);
    }
}
