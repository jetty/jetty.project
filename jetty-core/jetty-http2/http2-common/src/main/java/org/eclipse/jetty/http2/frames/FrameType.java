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

import java.util.HashMap;
import java.util.Map;

public enum FrameType
{
    DATA(0),
    HEADERS(1),
    PRIORITY(2),
    RST_STREAM(3),
    SETTINGS(4),
    PUSH_PROMISE(5),
    PING(6),
    GO_AWAY(7),
    WINDOW_UPDATE(8),
    CONTINUATION(9),
    // Synthetic frames only needed by the implementation.
    PREFACE(10),
    DISCONNECT(11),
    FAILURE(12);

    public static FrameType from(int type)
    {
        return Types.types.get(type);
    }

    private final int type;

    private FrameType(int type)
    {
        this.type = type;
        Types.types.put(type, this);
    }

    public int getType()
    {
        return type;
    }

    private static class Types
    {
        private static final Map<Integer, FrameType> types = new HashMap<>();
    }
}
