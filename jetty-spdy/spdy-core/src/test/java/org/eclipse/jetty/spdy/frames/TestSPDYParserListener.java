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

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.parser.Parser;

public class TestSPDYParserListener implements Parser.Listener
{
    private ControlFrame controlFrame;
    private DataFrame dataFrame;
    private ByteBuffer data;

    @Override
    public void onControlFrame(ControlFrame frame)
    {
        this.controlFrame = frame;
    }

    @Override
    public void onDataFrame(DataFrame frame, ByteBuffer data)
    {
        this.dataFrame = frame;
        this.data = data;
    }

    @Override
    public void onStreamException(StreamException x)
    {
    }

    @Override
    public void onSessionException(SessionException x)
    {
    }

    public ControlFrame getControlFrame()
    {
        return controlFrame;
    }

    public DataFrame getDataFrame()
    {
        return dataFrame;
    }

    public ByteBuffer getData()
    {
        return data;
    }
}
