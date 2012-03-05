/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    WINDOW_UPDATE((short)9);

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
