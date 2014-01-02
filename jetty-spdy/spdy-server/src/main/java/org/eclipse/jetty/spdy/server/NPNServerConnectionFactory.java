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

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NPNServerConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = Log.getLogger(NPNServerConnectionFactory.class);
    private final List<String> _protocols;
    private String _defaultProtocol;

    /* ------------------------------------------------------------ */
    /**
     * @param protocols List of supported protocols in priority order
     */
    public NPNServerConnectionFactory(@Name("protocols")String... protocols)
    {
        super("npn");
        _protocols=Arrays.asList(protocols);

        try
        {
            if (NextProtoNego.class.getClassLoader()!=null)
            {
                LOG.warn("NextProtoNego not from bootloader classloader: "+NextProtoNego.class.getClassLoader());
                throw new IllegalStateException("NextProtoNego not on bootloader");
            }
        }
        catch(Throwable th)
        {
            LOG.warn("NextProtoNego not available: "+th);
            throw new IllegalStateException("NextProtoNego not available",th);
        }
    }

    public String getDefaultProtocol()
    {
        return _defaultProtocol;
    }

    public void setDefaultProtocol(String defaultProtocol)
    {
        _defaultProtocol = defaultProtocol;
    }

    public List<String> getProtocols()
    {
        return _protocols;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        List<String> protocols=_protocols;
        if (protocols==null || protocols.size()==0)
        {
            protocols=connector.getProtocols();
            for (Iterator<String> i=protocols.iterator();i.hasNext();)
            {
                String protocol=i.next();
                if (protocol.startsWith("SSL-")||protocol.equals("NPN"))
                    i.remove();
            }
        }

        String dft=_defaultProtocol;
        if (dft==null)
            dft=_protocols.get(0);
        
        SSLEngine engine=null;
        EndPoint ep=endPoint;
        while(engine==null && ep!=null)
        {
            // TODO make more generic
            if (ep instanceof SslConnection.DecryptedEndPoint)
                engine=((SslConnection.DecryptedEndPoint)ep).getSslConnection().getSSLEngine();
            else
                ep=null;
        }

        return configure(new NPNServerConnection(endPoint, engine, connector,protocols,_defaultProtocol),connector,endPoint);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s,%s}",this.getClass().getSimpleName(),hashCode(),getProtocol(),getDefaultProtocol(),getProtocols());
    }
}
