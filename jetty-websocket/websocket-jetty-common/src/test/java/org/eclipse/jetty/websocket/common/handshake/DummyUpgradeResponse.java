//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.handshake;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;

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
}
