//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.io.http;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

public class HttpResponseParseCapture implements HttpResponseHeaderParseListener
{
    private int statusCode;
    private String statusReason;
    private Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private ByteBuffer remainingBuffer;

    @Override
    public void addHeader(String name, String value)
    {
        headers.put(name, value);
    }

    public String getHeader(String name)
    {
        return headers.get(name);
    }

    public ByteBuffer getRemainingBuffer()
    {
        return remainingBuffer;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public String getStatusReason()
    {
        return statusReason;
    }

    @Override
    public void setRemainingBuffer(ByteBuffer copy)
    {
        this.remainingBuffer = copy;
    }

    @Override
    public void setStatusCode(int code)
    {
        this.statusCode = code;
    }

    @Override
    public void setStatusReason(String reason)
    {
        this.statusReason = reason;
    }
}
