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

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;

public class RawHTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory
{
    private final Session.Listener listener;

    public RawHTTP2ServerConnectionFactory(Session.Listener listener)
    {
        this.listener = listener;
    }

    @Override
    protected Session.Listener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return listener;
    }
}
