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

package org.eclipse.jetty.http2;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HTTP2Session implements Session, Parser.Listener
{
    private static final Logger LOG = Log.getLogger(HTTP2Session.class);

    private final Listener listener;

    public HTTP2Session(Session.Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public boolean onData(DataFrame frame)
    {
        return false;
    }

    @Override
    public abstract boolean onHeaders(HeadersFrame frame);

    @Override
    public boolean onPriority(PriorityFrame frame)
    {
        return false;
    }

    @Override
    public boolean onReset(ResetFrame frame)
    {
        return false;
    }

    @Override
    public boolean onSettings(SettingsFrame frame)
    {
        return false;
    }

    @Override
    public boolean onPing(PingFrame frame)
    {
        return false;
    }

    @Override
    public boolean onGoAway(GoAwayFrame frame)
    {
        return false;
    }

    @Override
    public boolean onWindowUpdate(WindowUpdateFrame frame)
    {
        return false;
    }

    @Override
    public void onConnectionFailure(int error, String reason)
    {

    }

    @Override
    public void newStream(HeadersFrame frame, Stream.Listener listener, Promise<Stream> promise)
    {

    }

    @Override
    public void settings(SettingsFrame frame, Callback callback)
    {

    }

    @Override
    public void ping(PingFrame frame, Callback callback)
    {

    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {

    }

    @Override
    public void close(GoAwayFrame frame, Callback callback)
    {

    }

    protected Stream.Listener notifyNewStream(Stream stream, HeadersFrame frame)
    {
        try
        {
            return listener.onNewStream(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return null;
        }
    }
}
