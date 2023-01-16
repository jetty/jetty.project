//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.transport;

import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;

public class HttpClientConnectionFactory implements ClientConnectionFactory
{
    /**
     * <p>Representation of the {@code HTTP/1.1} application protocol used by {@link HttpClientTransportDynamic}.</p>
     */
    public static final Info HTTP11 = new HTTP11(new HttpClientConnectionFactory());

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, context);
        return customize(connection, context);
    }

    private static class HTTP11 extends Info
    {
        private static final List<String> protocols = List.of("http/1.1");

        private HTTP11(ClientConnectionFactory factory)
        {
            super(factory);
        }

        @Override
        public List<String> getProtocols(boolean secure)
        {
            return protocols;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x%s", getClass().getSimpleName(), hashCode(), protocols);
        }
    }
}
