//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.frames;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * A Data Frame
 */
public class DataFrame extends WebSocketFrame
{
    public DataFrame(byte opcode)
    {
        super(opcode);
    }

    protected DataFrame(byte opCode, Frame basedOn)
    {
        super(opCode);
        copyHeaders(basedOn);
    }

    /**
     * Construct new DataFrame based on headers of provided frame.
     * <p>
     * Useful for when working in extensions and a new frame needs to be created.
     * @param basedOn the frame this one is based on
     * @return the newly constructed DataFrame
     */
    public static DataFrame newDataFrame(Frame basedOn)
    {
        return newDataFrame(basedOn, false);
    }


    /**
     * Construct new DataFrame based on headers of provided frame, overriding for continuations if needed.
     * <p>
     * Useful for when working in extensions and a new frame needs to be created.
     * @param basedOn the frame this one is based on
     * @param continuation true if this is a continuation frame
     * @return the newly constructed DataFrame
     */
    public static DataFrame newDataFrame(Frame basedOn, boolean continuation)
    {
        if(continuation || (basedOn.getType() == Type.CONTINUATION))
            return new ContinuationFrame(basedOn);

        if(basedOn.getType() == Type.BINARY)
            return new BinaryFrame(basedOn);

        if(basedOn.getType() == Type.TEXT)
            return new TextFrame(basedOn);

        throw new IllegalArgumentException("Invalid FrameType");
    }

    @Override
    public final boolean isControlFrame()
    {
        return false;
    }

    @Override
    public final boolean isDataFrame()
    {
        return true;
    }
    
    @Override
    public String toString()
    {
        return super.toString()+BufferUtil.toDetailString(payload);
    }
}
