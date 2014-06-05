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
import org.eclipse.jetty.http2.frames.PriorityFrame;
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

    protected boolean isPaddingHigh()
    {
        return headerParser.isPaddingHigh();
    }

    protected boolean isPaddingLow()
    {
        return headerParser.isPaddingLow();
    }

    protected boolean isEndStream()
    {
        return headerParser.isEndStream();
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

    protected boolean notifyDataFrame(DataFrame frame)
    {
        try
        {
            return listener.onDataFrame(frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    protected boolean notifyPriorityFrame(PriorityFrame frame)
    {
        try
        {
            return listener.onPriorityFrame(frame);
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
