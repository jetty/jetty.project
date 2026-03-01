//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.TypeUtil;

public class HttpClientConnectionFactory implements ClientConnectionFactory
{
    /**
     * <p>Representation of the {@code HTTP/1.1} application protocol used by {@link HttpClientTransportDynamic}.</p>
     */
    public static final Info HTTP11 = new HTTP11();

    private boolean initializeConnections;
    private int transferEncodingChunkMaxLength = HttpGenerator.DEFAULT_CHUNK_MAX_LENGTH;

    /**
     * @return whether newly created connections should be initialized with an {@code OPTIONS * HTTP/1.1} request
     */
    public boolean isInitializeConnections()
    {
        return initializeConnections;
    }

    /**
     * @param initialize whether newly created connections should be initialized with an {@code OPTIONS * HTTP/1.1} request
     */
    public void setInitializeConnections(boolean initialize)
    {
        this.initializeConnections = initialize;
    }

    /**
     * @return the transfer-encoding content chunk max length
     */
    public int getTransferEncodingChunkMaxLength()
    {
        return transferEncodingChunkMaxLength;
    }

    /**
     * @param chunkMaxLength the transfer-encoding content chunk max length
     */
    public void setTransferEncodingChunkMaxLength(int chunkMaxLength)
    {
        if (transferEncodingChunkMaxLength <= 0)
            throw new IllegalArgumentException("invalid transfer-encoding chunk max length");
        transferEncodingChunkMaxLength = chunkMaxLength;
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, context);
        connection.setInitialize(isInitializeConnections());
        connection.setTransferEncodingChunkMaxLength(getTransferEncodingChunkMaxLength());
        return customize(connection, context);
    }

    /**
     * <p>Representation of the {@code HTTP/1.1} application protocol used by {@link HttpClientTransportDynamic}.</p>
     * <p>Applications should prefer using the constant {@link HttpClientConnectionFactory#HTTP11}, unless they
     * need to customize the associated {@link HttpClientConnectionFactory}.</p>
     */
    public static class HTTP11 extends Info
    {
        private final List<String> protocols;

        public HTTP11()
        {
            this(List.of("http/1.1"));
        }

        public HTTP11(List<String> protocols)
        {
            super(new HttpClientConnectionFactory());
            this.protocols = protocols;
        }

        @Override
        public List<String> getProtocols(boolean secure)
        {
            return protocols;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x%s", TypeUtil.toShortName(getClass()), hashCode(), protocols);
        }
    }
}
