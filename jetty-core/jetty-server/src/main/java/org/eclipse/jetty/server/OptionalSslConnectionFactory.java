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

package org.eclipse.jetty.server;

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A ConnectionFactory whose connections detect whether the first bytes are
 * TLS bytes and upgrades to either a TLS connection or to another configurable
 * connection.</p>
 *
 * @deprecated Use {@link DetectorConnectionFactory} with a {@link SslConnectionFactory} instead.
 */
@Deprecated
public class OptionalSslConnectionFactory extends DetectorConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(OptionalSslConnectionFactory.class);
    private final String _nextProtocol;

    /**
     * <p>Creates a new ConnectionFactory whose connections can upgrade to TLS or another protocol.</p>
     *
     * @param sslConnectionFactory The {@link SslConnectionFactory} to use if the first bytes are TLS
     * @param nextProtocol the protocol of the {@link ConnectionFactory} to use if the first bytes are not TLS,
     * or null to explicitly handle the non-TLS case
     */
    public OptionalSslConnectionFactory(SslConnectionFactory sslConnectionFactory, String nextProtocol)
    {
        super(sslConnectionFactory);
        _nextProtocol = nextProtocol;
    }

    /**
     * <p>Callback method invoked when the detected bytes are not TLS.</p>
     * <p>This typically happens when a client is trying to connect to a TLS
     * port using the {@code http} scheme (and not the {@code https} scheme).</p>
     *
     * @param connector The connector object
     * @param endPoint The connection EndPoint object
     * @param buffer The buffer with the first bytes of the connection
     */
    protected void nextProtocol(Connector connector, EndPoint endPoint, RetainableByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("OptionalSSL TLS detection unsuccessful, attempting to upgrade to {}", _nextProtocol);
        if (_nextProtocol != null)
        {
            ConnectionFactory connectionFactory = connector.getConnectionFactory(_nextProtocol);
            if (connectionFactory == null)
                throw new IllegalStateException("Cannot find protocol '" + _nextProtocol + "' in connector's protocol list " + connector.getProtocols() + " for " + endPoint);
            upgradeToConnectionFactory(connectionFactory, connector, endPoint);
        }
        else
        {
            otherProtocol(endPoint, buffer);
        }
    }

    private void otherProtocol(EndPoint endPoint, RetainableByteBuffer buffer)
    {
        LOG.warn("Detected non-TLS bytes, but no other protocol to upgrade to for {}", endPoint);

        // There are always at least 2 bytes.
        int byte1 = buffer.get() & 0xFF;
        int byte2 = buffer.get() & 0xFF;
        if (byte1 == 'G' && byte2 == 'E')
        {
            // Plain text HTTP to an HTTPS port,
            // write a minimal response.
            String body = """
                <!DOCTYPE html>\r
                <html>\r
                <head><title>Bad Request</title></head>\r
                <body>\r
                <h1>Bad Request</h1>\r
                <p>HTTP request to HTTPS port</p>\r
                </body>\r
                </html>""";
            String response = """
                HTTP/1.1 400 Bad Request\r
                Content-Type: text/html\r
                Content-Length: %d\r
                Connection: close\r
                \r
                %s""".formatted(body.length(), body);
            endPoint.write(RetainableByteBuffer.wrap(response, StandardCharsets.US_ASCII), Callback.from(endPoint::close));
        }
        else
        {
            endPoint.close();
        }
    }
}
