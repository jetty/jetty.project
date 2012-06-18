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

package org.eclipse.jetty.websocket.frames;

import java.util.HashMap;
import java.util.Map;

public enum ControlFrameType
{
    PING_FRAME(BaseFrame.OP_PING),
    PONG_FRAME(BaseFrame.OP_PONG),
    CLOSE_FRAME(BaseFrame.OP_CLOSE);

    private static class Codes
    {
        private static final Map<Byte, ControlFrameType> codes = new HashMap<>();
    }

    public static ControlFrameType from(byte opcode)
    {
        return Codes.codes.get(opcode);
    }

    private final byte opcode;

    private ControlFrameType(byte opcode)
    {
        this.opcode = opcode;
        Codes.codes.put(opcode,this);
    }

    public short getCode()
    {
        return opcode;
    }
}
