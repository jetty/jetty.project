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

package org.eclipse.jetty.spdy.server;

import java.util.List;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NegotiatingServerConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NPNServerConnection extends NegotiatingServerConnection implements NextProtoNego.ServerProvider
{
    private static final Logger LOG = Log.getLogger(NPNServerConnection.class);

    public NPNServerConnection(EndPoint endPoint, SSLEngine engine, Connector connector, List<String> protocols, String defaultProtocol)
    {
        super(connector, endPoint, engine, protocols, defaultProtocol);
        NextProtoNego.put(engine, this);
    }

    @Override
    public void unsupported()
    {
        protocolSelected(getDefaultProtocol());
    }

    @Override
    public List<String> protocols()
    {
        return getProtocols();
    }

    @Override
    public void protocolSelected(String protocol)
    {
        LOG.debug("{} protocol selected {}", this, protocol);
        setProtocol(protocol != null ? protocol : getDefaultProtocol());
        NextProtoNego.remove(getSSLEngine());
    }

    @Override
    public void close()
    {
        NextProtoNego.remove(getSSLEngine());
        super.close();
    }
}
