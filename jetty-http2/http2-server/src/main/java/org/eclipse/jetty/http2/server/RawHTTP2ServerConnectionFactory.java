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

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public class RawHTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory
{
    private final ServerSessionListener listener;

    public RawHTTP2ServerConnectionFactory(HttpConfiguration httpConfiguration,ServerSessionListener listener)
    {
        super(httpConfiguration);
        this.listener = listener;
    }

    public RawHTTP2ServerConnectionFactory(HttpConfiguration httpConfiguration, ServerSessionListener listener, String... protocols)
    {
        super(httpConfiguration,protocols);
        this.listener = listener;
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return listener;
    }
}
