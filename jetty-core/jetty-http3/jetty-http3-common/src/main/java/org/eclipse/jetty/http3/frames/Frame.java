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

package org.eclipse.jetty.http3.frames;

public abstract class Frame
{
    public static boolean isLast(Frame frame)
    {
        return frame instanceof HeadersFrame && ((HeadersFrame)frame).isLast() ||
            frame instanceof DataFrame && ((DataFrame)frame).isLast();
    }

    private final FrameType type;

    public Frame(FrameType type)
    {
        this.type = type;
    }

    public FrameType getFrameType()
    {
        return type;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
