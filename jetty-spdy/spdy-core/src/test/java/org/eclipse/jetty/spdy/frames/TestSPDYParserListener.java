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
