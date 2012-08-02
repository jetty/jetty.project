// ========================================================================
// Copyright (c) 2004-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

// TODO This is just a place holder for a real factory 
public class ConnectionFactory extends AggregateLifeCycle
{
    private final boolean _ssl;
    private final SslContextFactory _sslContextFactory;


    // TODO  Make this part of pluggable factory
    private final HttpConfiguration _httpConfig;
    
    
    ConnectionFactory()
    {
        this(null,false);
    }

    /* ------------------------------------------------------------ */
    /**     
     * @param sslContextFactory An SslContextFactory to use or null if no ssl is required or to use default {@link SslContextFactory} 
     * @param ssl If true, then new connections will assumed to be SSL. If false, connections can only become SSL if they upgrade and a SslContextFactory is passed.
     */
    ConnectionFactory(SslContextFactory sslContextFactory, boolean ssl)
    {
        this(null,sslContextFactory,ssl);
    }

    ConnectionFactory(HttpConfiguration httpConfig, SslContextFactory sslContextFactory, boolean ssl)
    {
        _ssl=ssl;
        _sslContextFactory=sslContextFactory!=null?sslContextFactory:(ssl?new SslContextFactory(SslContextFactory.DEFAULT_KEYSTORE_PATH):null);
        
        addBean(_sslContextFactory,sslContextFactory==null);

        // TODO make this pluggable
        _httpConfig = httpConfig!=null?httpConfig:new HttpConfiguration(_sslContextFactory,ssl);
        addBean(_httpConfig,httpConfig==null);

    }
    

    protected Connection newConnection(Connector connector,EndPoint endp) throws IOException
    {
        
        if (_ssl)
        {
            SSLEngine engine = _sslContextFactory.createSSLEngine(endp.getRemoteAddress());
            SslConnection ssl_connection = new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endp, engine);
            
            Connection http_connection = new HttpConnection(_httpConfig,connector,ssl_connection.getDecryptedEndPoint());
            ssl_connection.getDecryptedEndPoint().setConnection(http_connection);
            return ssl_connection;
        }
        return new HttpConnection(_httpConfig,connector,endp);
    }

    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    public HttpConfiguration getHttpConfig()
    {
        return _httpConfig;
    }
    

}
