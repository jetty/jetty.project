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

import java.util.HashMap;
import java.util.Map;

public enum FrameType
{
    DATA(0x0),
    HEADERS(0x1),
    CANCEL_PUSH(0x3),
    SETTINGS(0x4),
    PUSH_PROMISE(0x5),
    GOAWAY(0x7),
    MAX_PUSH_ID(0xD);

    public static FrameType from(long type)
    {
        return Types.types.get(type);
    }

    public static boolean isControl(long frameType)
    {
        return frameType == CANCEL_PUSH.type() ||
            frameType == SETTINGS.type() ||
            frameType == GOAWAY.type() ||
            frameType == MAX_PUSH_ID.type();
    }

    public static boolean isMessage(long frameType)
    {
        return frameType == DATA.type() ||
            frameType == HEADERS.type() ||
            frameType == PUSH_PROMISE.type();
    }

    public static int maxType()
    {
        return MAX_PUSH_ID.type();
    }

    private final int type;

    private FrameType(int type)
    {
        this.type = type;
        Types.types.put((long)type, this);
    }

    public int type()
    {
        return type;
    }

    private static class Types
    {
        private static final Map<Long, FrameType> types = new HashMap<>();
    }
}
