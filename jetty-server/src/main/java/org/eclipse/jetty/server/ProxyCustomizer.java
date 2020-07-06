//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Attributes;

/**
 * <p>Customizer that extracts the real local and remote address:port pairs from a {@link ProxyConnectionFactory}
 * and sets them on the request with {@link ServletRequest#setAttribute(String, Object)}.
 */
public class ProxyCustomizer implements HttpConfiguration.Customizer
{
    /**
     * The remote address attribute name.
     */
    public static final String REMOTE_ADDRESS_ATTRIBUTE_NAME = "org.eclipse.jetty.proxy.remote.address";

    /**
     * The remote port attribute name.
     */
    public static final String REMOTE_PORT_ATTRIBUTE_NAME = "org.eclipse.jetty.proxy.remote.port";

    /**
     * The local address attribute name.
     */
    public static final String LOCAL_ADDRESS_ATTRIBUTE_NAME = "org.eclipse.jetty.proxy.local.address";

    /**
     * The local port attribute name.
     */
    public static final String LOCAL_PORT_ATTRIBUTE_NAME = "org.eclipse.jetty.proxy.local.port";

    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        EndPoint endPoint = request.getHttpChannel().getEndPoint();
        if (endPoint instanceof ProxyConnectionFactory.ProxyEndPoint)
        {
            EndPoint underlyingEndpoint = ((ProxyConnectionFactory.ProxyEndPoint)endPoint).unwrap();
            request.setAttributes(new ProxyAttributes(underlyingEndpoint.getRemoteAddress(), underlyingEndpoint.getLocalAddress(), request.getAttributes()));
        }
    }

    private static class ProxyAttributes extends Attributes.Wrapper
    {
        private final String _remoteAddress;
        private final String _localAddress;
        private final int _remotePort;
        private final int _localPort;

        private ProxyAttributes(InetSocketAddress remoteAddress, InetSocketAddress localAddress, Attributes attributes)
        {
            super(attributes);
            _remoteAddress = remoteAddress.getAddress().getHostAddress();
            _localAddress = localAddress.getAddress().getHostAddress();
            _remotePort = remoteAddress.getPort();
            _localPort = localAddress.getPort();
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                case REMOTE_ADDRESS_ATTRIBUTE_NAME:
                    return _remoteAddress;
                case REMOTE_PORT_ATTRIBUTE_NAME:
                    return _remotePort;
                case LOCAL_ADDRESS_ATTRIBUTE_NAME:
                    return _localAddress;
                case LOCAL_PORT_ATTRIBUTE_NAME:
                    return _localPort;
                default:
                    return super.getAttribute(name);
            }
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(_attributes.getAttributeNameSet());
            names.remove(REMOTE_ADDRESS_ATTRIBUTE_NAME);
            names.remove(LOCAL_ADDRESS_ATTRIBUTE_NAME);

            if (_remoteAddress != null)
                names.add(REMOTE_ADDRESS_ATTRIBUTE_NAME);
            if (_localAddress != null)
                names.add(LOCAL_ADDRESS_ATTRIBUTE_NAME);
            names.add(REMOTE_PORT_ATTRIBUTE_NAME);
            names.add(LOCAL_PORT_ATTRIBUTE_NAME);
            return names;
        }
    }
}
