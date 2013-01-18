//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.extensions.mux;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.websocket.core.api.UpgradeException;
import org.eclipse.jetty.websocket.core.api.UpgradeResponse;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;

public class MuxResponse implements UpgradeResponse
{
    @Override
    public void addHeader(String name, String value)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<String> getHeaderNamesSet()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getHeaderValue(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Iterator<String> getHeaderValues(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getStatusCode()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String getStatusReason()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean isSuccess()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setHeader(String name, String value)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void validateWebSocketHash(String expectedHash) throws UpgradeException
    {
        // TODO Auto-generated method stub

    }

}
