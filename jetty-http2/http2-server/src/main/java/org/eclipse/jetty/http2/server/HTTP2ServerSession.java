//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;

public class HTTP2ServerSession extends HTTP2Session
{
    private final ConcurrentMap<Integer, IStream> streams = new ConcurrentHashMap<>();

    public HTTP2ServerSession(Listener listener)
    {
        super(listener);
    }

    @Override
    public boolean onHeaders(HeadersFrame frame)
    {
        // TODO: handle max concurrent streams
        // TODO: handle duplicate streams

        IStream stream = new HTTP2Stream();
        IStream existing = streams.putIfAbsent(stream.getId(), stream);
        if (existing == null)
        {
            Stream.Listener listener = notifyNewStream(stream, frame);
            stream.setListener(listener);
        }
        return false;
    }
}
