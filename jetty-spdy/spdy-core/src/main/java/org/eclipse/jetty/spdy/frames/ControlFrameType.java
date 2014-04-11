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

import java.util.HashMap;
import java.util.Map;

public enum ControlFrameType
{
    SYN_STREAM((short)1),
    SYN_REPLY((short)2),
    RST_STREAM((short)3),
    SETTINGS((short)4),
    NOOP((short)5),
    PING((short)6),
    GO_AWAY((short)7),
    HEADERS((short)8),
    WINDOW_UPDATE((short)9),
    CREDENTIAL((short)10);

    public static ControlFrameType from(short code)
    {
        return Codes.codes.get(code);
    }

    private final short code;

    private ControlFrameType(short code)
    {
        this.code = code;
        Codes.codes.put(code, this);
    }

    public short getCode()
    {
        return code;
    }

    private static class Codes
    {
        private static final Map<Short, ControlFrameType> codes = new HashMap<>();
    }
}
