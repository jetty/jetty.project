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

package org.eclipse.jetty.spdy.http;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.spdy.api.SPDY;

/**
 * <p>{@link HTTPSPDYHeader} defines the SPDY headers that are not also HTTP headers,
 * such as <tt>method</tt>, <tt>version</tt>, etc. or that are treated differently
 * by the SPDY protocol, such as <tt>host</tt>.</p>
 */
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
