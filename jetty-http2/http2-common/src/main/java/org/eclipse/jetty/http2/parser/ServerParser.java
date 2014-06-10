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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServerParser extends Parser
{
    private static final Logger LOG = Log.getLogger(ServerParser.class);

    private final Listener listener;
    private final PrefaceParser prefaceParser;
    private State state = State.PREFACE;

    public ServerParser(ByteBufferPool byteBufferPool, Listener listener)
    {
        super(byteBufferPool, listener);
        this.listener = listener;
        this.prefaceParser = new PrefaceParser(listener);
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        LOG.debug("Parsing {}", buffer);

        while (true)
        {
            switch (state)
            {
                case PREFACE:
                {
                    if (!prefaceParser.parse(buffer))
                        return false;
                    if (onPreface())
                        return true;
                    state = State.FRAMES;
                    break;
                }
                case FRAMES:
                {
                    // Stay forever in the FRAMES state.
                    return super.parse(buffer);
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    protected boolean onPreface()
    {
        return notifyPreface();
    }

    private boolean notifyPreface()
    {
        try
        {
            return listener.onPreface();
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
            return false;
        }
    }

    public interface Listener extends Parser.Listener
    {
        public boolean onPreface();

        public static class Adapter extends Parser.Listener.Adapter implements Listener
        {
            @Override
            public boolean onPreface()
            {
                return false;
            }
        }
    }

    private enum State
    {
        PREFACE, FRAMES
    }
}
