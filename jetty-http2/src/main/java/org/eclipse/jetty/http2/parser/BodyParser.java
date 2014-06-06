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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class BodyParser
{
    protected static final Logger LOG = Log.getLogger(BodyParser.class);

    private final HeaderParser headerParser;
    private final Parser.Listener listener;

    protected BodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        this.headerParser = headerParser;
        this.listener = listener;
    }

    public abstract Result parse(ByteBuffer buffer);

    protected boolean hasFlag(int bit)
    {
        return headerParser.hasFlag(bit);
    }

    protected boolean isPaddingHigh()
    {
        return headerParser.hasFlag(0x10);
    }

    protected boolean isPaddingLow()
    {
        return headerParser.hasFlag(0x8);
    }

    protected boolean isEndStream()
    {
        return headerParser.hasFlag(0x1);
    }

    protected int getStreamId()
    {
        return headerParser.getStreamId();
    }

    protected int getBodyLength()
    {
        return headerParser.getLength();
    }

    protected void reset()
    {
        headerParser.reset();
    }

    protected boolean notifyData(DataFrame frame)
    {
        try
        {
            return listener.onData(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    protected boolean notifyPriority(PriorityFrame frame)
    {
        try
        {
            return listener.onPriority(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    protected boolean notifyReset(ResetFrame frame)
    {
        try
        {
            return listener.onReset(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    protected boolean notifyPing(PingFrame frame)
    {
        try
        {
            return listener.onPing(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    protected boolean notifyGoAway(GoAwayFrame frame)
    {
        try
        {
            return listener.onGoAway(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    protected boolean notifyWindowUpdate(WindowUpdateFrame frame)
    {
        try
        {
            return listener.onWindowUpdate(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    public enum Result
    {
        PENDING, ASYNC, COMPLETE
    }
}
