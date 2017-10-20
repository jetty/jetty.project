//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.handshake;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

public class DummyUpgradeResponse implements UpgradeResponse
{
    @Override
    public void addHeader(String name, String value)
    {
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return null;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return null;
    }

    @Override
    public String getHeader(String name)
    {
        return null;
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return null;
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return null;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return null;
    }

    @Override
    public int getStatusCode()
    {
        return 0;
    }

    @Override
    public String getStatusReason()
    {
        return null;
    }

    @Override
    public boolean isSuccess()
    {
        return false;
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {

    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {

    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {

    }

    @Override
    public void setHeader(String name, String value)
    {

    }

    @Override
    public void setStatusCode(int statusCode)
    {

    }

    @Override
    public void setStatusReason(String statusReason)
    {

    }

    @Override
    public void setSuccess(boolean success)
    {

    }
}
