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

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Parser
{
    private static final Logger logger = Log.getLogger(Parser.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ControlFrameParser controlFrameParser;
    private final DataFrameParser dataFrameParser;
    private State state = State.CONTROL_BIT;

    public Parser(CompressionFactory.Decompressor decompressor)
    {
        // It is important to allocate one decompression context per
        // SPDY session for the control frames (to decompress the headers)
        controlFrameParser = new ControlFrameParser(decompressor)
        {
            @Override
            protected void onControlFrame(ControlFrame frame)
            {
                logger.debug("Parsed {}", frame);
                notifyControlFrame(frame);
            }
        };
        dataFrameParser = new DataFrameParser()
        {
            @Override
            protected void onDataFrame(DataFrame frame, ByteBuffer data)
            {
                logger.debug("Parsed {}, {} data bytes", frame, data.remaining());
                notifyDataFrame(frame, data);
            }
        };
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    protected void notifyControlFrame(ControlFrame frame)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onControlFrame(frame);
            }
            catch (Exception x)
            {
                logger.info("Exception while notifying listener " + listener, x);
            }
        }
    }

    protected void notifyDataFrame(DataFrame frame, ByteBuffer data)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onDataFrame(frame, data);
            }
            catch (Exception x)
            {
                logger.info("Exception while notifying listener " + listener, x);
            }
        }
    }

    protected void notifyStreamException(StreamException x)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onStreamException(x);
            }
            catch (Exception xx)
            {
                logger.debug("Could not notify listener " + listener, xx);
            }
        }
    }

    protected void notifySessionException(SessionException x)
    {
        logger.debug("SPDY session exception", x);
        for (Listener listener : listeners)
        {
            try
            {
                listener.onSessionException(x);
            }
            catch (Exception xx)
            {
                logger.debug("Could not notify listener " + listener, xx);
            }
        }
    }

    public void parse(ByteBuffer buffer)
    {
        logger.debug("Parsing {} bytes", buffer.remaining());
        try
        {
            while (buffer.hasRemaining())
            {
                try
                {
                    switch (state)
                    {
                        case CONTROL_BIT:
                        {
                            // We must only peek the first byte and not advance the buffer
                            // because the 7 least significant bits may be relevant in data frames
                            int currByte = buffer.get(buffer.position());
                            boolean isControlFrame = (currByte & 0x80) == 0x80;
                            state = isControlFrame ? State.CONTROL_FRAME : State.DATA_FRAME;
                            break;
                        }
                        case CONTROL_FRAME:
                        {
                            if (controlFrameParser.parse(buffer))
                                reset();
                            break;
                        }
                        case DATA_FRAME:
                        {
                            if (dataFrameParser.parse(buffer))
                                reset();
                            break;
                        }
                        default:
                        {
                            throw new IllegalStateException();
                        }
                    }
                }
                catch (StreamException x)
                {
                    notifyStreamException(x);
                }
            }
        }
        catch (SessionException x)
        {
            notifySessionException(x);
        }
        catch (Throwable x)
        {
            notifySessionException(new SessionException(SessionStatus.PROTOCOL_ERROR, x));
        }
        finally
        {
            // Be sure to consume after exceptions
            buffer.position(buffer.limit());
        }
    }

    private void reset()
    {
        state = State.CONTROL_BIT;
    }

    public interface Listener extends EventListener
    {
        public void onControlFrame(ControlFrame frame);

        public void onDataFrame(DataFrame frame, ByteBuffer data);

        public void onStreamException(StreamException x);

        public void onSessionException(SessionException x);

        public static class Adapter implements Listener
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
            }

            @Override
            public void onDataFrame(DataFrame frame, ByteBuffer data)
            {
            }

            @Override
            public void onStreamException(StreamException x)
            {
            }

            @Override
            public void onSessionException(SessionException x)
            {
            }
        }
    }

    private enum State
    {
        CONTROL_BIT, CONTROL_FRAME, DATA_FRAME
    }
}
