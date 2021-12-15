//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Attributes;

public class MockConnectionMetaData extends Attributes.Mapped implements ConnectionMetaData
{
    boolean _persistent = true;

    public void notPersistent()
    {
        _persistent = false;
    }

    @Override
    public String getId()
    {
        return "test";
    }

    @Override
    public HttpVersion getVersion()
    {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public String getProtocol()
    {
        return "http";
    }

    @Override
    public Connection getConnection()
    {
        return null;
    }

    @Override
    public Connector getConnector()
    {
        return null;
    }

    @Override
    public boolean isPersistent()
    {
        return _persistent;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public SocketAddress getRemote()
    {
        return InetSocketAddress.createUnresolved("localhost", 12345);
    }

    @Override
    public SocketAddress getLocal()
    {
        return InetSocketAddress.createUnresolved("localhost", 80);
    }
}
