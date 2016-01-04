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
    DISCONNECT(11);

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
