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

public abstract class ControlFrame
{
    public static final int HEADER_LENGTH = 8;

    private final short version;
    private final ControlFrameType type;
    private final byte flags;

    public ControlFrame(short version, ControlFrameType type, byte flags)
    {
        this.version = version;
        this.type = type;
        this.flags = flags;
    }

    public short getVersion()
    {
        return version;
    }

    public ControlFrameType getType()
    {
        return type;
    }

    public byte getFlags()
    {
        return flags;
    }

    @Override
    public String toString()
    {
        return String.format("%s frame v%s", getType(), getVersion());
    }
}
