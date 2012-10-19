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

package org.eclipse.jetty.server.ssl;

import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/* ------------------------------------------------------------ */
/**
 * SslSelectChannelConnector.
 *
 * @deprecated use SelectChannelConnector with {@link SslContextFactory}
 * @org.apache.xbean.XBean element="sslConnector" description="Creates an NIO ssl connector"
 */
public class SslSelectChannelConnector extends ServerConnector
{
    public SslSelectChannelConnector(Server server)
    {
        super(server,null,null,null,0,0,AbstractConnectionFactory.getFactories(new SslContextFactory(),new HttpConnectionFactory()));
    }
}
