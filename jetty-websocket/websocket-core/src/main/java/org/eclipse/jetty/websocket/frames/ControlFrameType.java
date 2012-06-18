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

import org.eclipse.jetty.websocket.frames.ControlFrameType;

public enum ControlFrameType 
{
	BASE_FRAME((short)1);
	
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
