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

package org.eclipse.jetty.fcgi.server;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class ServerFCGIConnectionFactory extends AbstractConnectionFactory
{
    private final HttpConfiguration configuration;
    private final boolean sendStatus200;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;

    public ServerFCGIConnectionFactory(HttpConfiguration configuration)
    {
        this(configuration, true);
    }

    public ServerFCGIConnectionFactory(HttpConfiguration configuration, boolean sendStatus200)
    {
        super("fcgi/1.0");
        this.configuration = configuration;
        this.sendStatus200 = sendStatus200;
        setUseInputDirectByteBuffers(configuration.isUseInputDirectByteBuffers());
        setUseOutputDirectByteBuffers(configuration.isUseOutputDirectByteBuffers());
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for reading")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for writing")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        ServerFCGIConnection connection = new ServerFCGIConnection(connector, endPoint, configuration, sendStatus200);
        connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
        return configure(connection, connector, endPoint);
    }
}
