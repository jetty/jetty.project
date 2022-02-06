//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
