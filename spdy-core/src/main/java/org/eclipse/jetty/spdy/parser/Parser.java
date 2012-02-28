/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Parser
{
    private static final Logger logger = LoggerFactory.getLogger(Parser.class);
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
            listener.onStreamException(x);
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
        try
        {
            logger.debug("Parsing {} bytes", buffer.remaining());
            while (buffer.hasRemaining())
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
        }
        catch (SessionException x)
        {
            notifySessionException(x);
        }
        catch (StreamException x)
        {
            notifyStreamException(x);
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
