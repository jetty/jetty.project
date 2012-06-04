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

package org.eclipse.jetty.spdy.http;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.spdy.api.SPDY;

public enum HTTPSPDYHeader
{
    METHOD("method", ":method"),
    URI("url", ":path"),
    VERSION("version", ":version"),
    SCHEME("scheme", ":scheme"),
    HOST("host", ":host"),
    STATUS("status", ":status");

    public static HTTPSPDYHeader from(short version, String name)
    {
        switch (version)
        {
            case SPDY.V2:
                return Names.v2Names.get(name);
            case SPDY.V3:
                return Names.v3Names.get(name);
            default:
                throw new IllegalStateException();
        }
    }

    private final String v2Name;
    private final String v3Name;

    private HTTPSPDYHeader(String v2Name, String v3Name)
    {
        this.v2Name = v2Name;
        Names.v2Names.put(v2Name, this);
        this.v3Name = v3Name;
        Names.v3Names.put(v3Name, this);
    }

    public String name(short version)
    {
        switch (version)
        {
            case SPDY.V2:
                return v2Name;
            case SPDY.V3:
                return v3Name;
            default:
                throw new IllegalStateException();
        }
    }

    private static class Names
    {
        private static final Map<String, HTTPSPDYHeader> v2Names = new HashMap<>();
        private static final Map<String, HTTPSPDYHeader> v3Names = new HashMap<>();
    }
}
