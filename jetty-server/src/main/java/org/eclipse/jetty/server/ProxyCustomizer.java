//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
        private final InetSocketAddress remoteAddress;
        private final InetSocketAddress localAddress;

        private ProxyAttributes(InetSocketAddress remoteAddress, InetSocketAddress localAddress, Attributes attributes)
        {
            super(attributes);
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                case REMOTE_ADDRESS_ATTRIBUTE_NAME:
                    return remoteAddress.getAddress().getHostAddress();
                case REMOTE_PORT_ATTRIBUTE_NAME:
                    return remoteAddress.getPort();
                case LOCAL_ADDRESS_ATTRIBUTE_NAME:
                    return localAddress.getAddress().getHostAddress();
                case LOCAL_PORT_ATTRIBUTE_NAME:
                    return localAddress.getPort();
                default:
                    return super.getAttribute(name);
            }
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(_attributes.getAttributeNameSet());
            names.add(REMOTE_ADDRESS_ATTRIBUTE_NAME);
            names.add(REMOTE_PORT_ATTRIBUTE_NAME);
            names.add(LOCAL_ADDRESS_ATTRIBUTE_NAME);
            names.add(LOCAL_PORT_ATTRIBUTE_NAME);
            return names;
        }
    }
}
