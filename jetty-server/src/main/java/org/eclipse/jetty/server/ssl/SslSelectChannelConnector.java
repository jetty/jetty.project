// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server.ssl;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/* ------------------------------------------------------------ */
/**
 * SslSelectChannelConnector.
 *
 * @deprecated use SelectChannelConnector with {@link SslContextFactory}
 * @org.apache.xbean.XBean element="sslConnector" description="Creates an NIO ssl connector"
 */
public class SslSelectChannelConnector extends SelectChannelConnector 
{
    public SslSelectChannelConnector(Server server)
    {
        super(server,true);
    }
}
