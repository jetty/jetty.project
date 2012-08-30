//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class ClientUpgradeResponse implements UpgradeResponse
{
    public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    private int statusCode;
    private String statusReason;
    private MultiMap<String> headers;
    private List<ExtensionConfig> extensions;
    private boolean success = false;

    public ClientUpgradeResponse()
    {
        headers = new MultiMap<>();
    }

    @Override
    public void addHeader(String name, String value)
    {
        headers.add(name.toLowerCase(),value);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return headers.getValue(SEC_WEBSOCKET_PROTOCOL,0);
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return extensions;
    }

    @Override
    public Set<String> getHeaderNamesSet()
    {
        return headers.keySet();
    }

    @Override
    public String getHeaderValue(String name)
    {
        return headers.getValue(name.toLowerCase(),0);
    }

    @Override
    public Iterator<String> getHeaderValues(String name)
    {
        List<String> values = headers.getValues(name);
        if (values == null)
        {
            return Collections.emptyIterator();
        }
        return values.iterator();
    }

    @Override
    public int getStatusCode()
    {
        return statusCode;
    }

    @Override
    public String getStatusReason()
    {
        return statusReason;
    }

    @Override
    public boolean isSuccess()
    {
        return success;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        throw new UnsupportedOperationException("Not supported on client implementation");
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        headers.put(SEC_WEBSOCKET_PROTOCOL,protocol);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        if (this.extensions == null)
        {
            this.extensions = new ArrayList<>();
        }
        else
        {
            this.extensions.clear();
        }
        this.extensions.addAll(extensions);
    }

    @Override
    public void setHeader(String name, String value)
    {
        headers.putValues(name,value);
    }

    public void setStatusCode(int statusCode)
    {
        this.statusCode = statusCode;
    }

    public void setStatusReason(String statusReason)
    {
        this.statusReason = statusReason;
    }

    @Override
    public void validateWebSocketHash(String expectedHash) throws UpgradeException
    {
        String respHash = getHeaderValue("Sec-WebSocket-Accept");

        success = true;
        if (expectedHash.equals(respHash) == false)
        {
            success = false;
            throw new UpgradeException("Invalid Sec-WebSocket-Accept hash");
        }
    }
}
