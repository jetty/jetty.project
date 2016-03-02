//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.server;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public class ServerFCGIConnectionFactory extends AbstractConnectionFactory
{
    private final HttpConfiguration configuration;
    private final boolean sendStatus200;

    public ServerFCGIConnectionFactory(HttpConfiguration configuration)
    {
        this(configuration, true);
    }

    public ServerFCGIConnectionFactory(HttpConfiguration configuration, boolean sendStatus200)
    {
        super("fcgi/1.0");
        this.configuration = configuration;
        this.sendStatus200 = sendStatus200;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        return new ServerFCGIConnection(connector, endPoint, configuration, sendStatus200);
    }
}
