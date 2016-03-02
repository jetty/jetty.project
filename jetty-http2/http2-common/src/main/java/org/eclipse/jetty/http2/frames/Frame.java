//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.frames;

public abstract class Frame
{
    public static final int HEADER_LENGTH = 9;
    public static final int DEFAULT_MAX_LENGTH = 0x40_00;
    public static final int MAX_MAX_LENGTH = 0xFF_FF_FF;
    public static final Frame[] EMPTY_ARRAY = new Frame[0];

    private final FrameType type;

    protected Frame(FrameType type)
    {
        this.type = type;
    }

    public FrameType getType()
    {
        return type;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
