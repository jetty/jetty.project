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

import java.util.HashMap;

import org.eclipse.jetty.http2.FlowControl;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class HTTP2ServerSession extends HTTP2Session implements ServerParser.Listener
{
    public HTTP2ServerSession(EndPoint endPoint, Generator generator, Listener listener, FlowControl flowControl)
    {
        super(endPoint, generator, listener, flowControl, 2);
    }

    @Override
    public boolean onPreface()
    {
        // SPEC: send a SETTINGS frame upon receiving the preface.
        HashMap<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.HEADER_TABLE_SIZE, getGenerator().getHeaderTableSize());
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, getFlowControl().getInitialWindowSize());
        int maxConcurrentStreams = getMaxStreamCount();
        if (maxConcurrentStreams >= 0)
            settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
        SettingsFrame frame = new SettingsFrame(settings, false);
        settings(frame, disconnectCallback);
        return false;
    }

    @Override
    public boolean onHeaders(HeadersFrame frame)
    {
        IStream stream = createRemoteStream(frame);
        if (stream != null)
        {
            stream.updateClose(frame.isEndStream(), false);
            stream.process(frame, Callback.Adapter.INSTANCE);
            Stream.Listener listener = notifyNewStream(stream, frame);
            stream.setListener(listener);
            // The listener may have sent a frame that closed the stream.
            if (stream.isClosed())
                removeStream(stream, false);
        }
        return false;
    }
}
