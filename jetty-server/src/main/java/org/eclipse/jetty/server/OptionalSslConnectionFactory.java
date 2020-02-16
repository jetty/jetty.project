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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger(OptionalSslConnectionFactory.class);
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
    protected void nextProtocol(Connector connector, EndPoint endPoint, ByteBuffer buffer)
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
            otherProtocol(buffer, endPoint);
        }
    }

    /**
     * <p>Legacy callback method invoked when {@code nextProtocol} is {@code null}
     * and the first bytes are not TLS.</p>
     * <p>This typically happens when a client is trying to connect to a TLS
     * port using the {@code http} scheme (and not the {@code https} scheme).</p>
     * <p>This method is kept around for backward compatibility.</p>
     *
     * @param buffer The buffer with the first bytes of the connection
     * @param endPoint The connection EndPoint object
     * @deprecated Override {@link #nextProtocol(Connector, EndPoint, ByteBuffer)} instead.
     */
    @Deprecated
    protected void otherProtocol(ByteBuffer buffer, EndPoint endPoint)
    {
        LOG.warn("Detected non-TLS bytes, but no other protocol to upgrade to for {}", endPoint);

        // There are always at least 2 bytes.
        int byte1 = buffer.get(0) & 0xFF;
        int byte2 = buffer.get(1) & 0xFF;
        if (byte1 == 'G' && byte2 == 'E')
        {
            // Plain text HTTP to an HTTPS port,
            // write a minimal response.
            String body =
                "<!DOCTYPE html>\r\n" +
                    "<html>\r\n" +
                    "<head><title>Bad Request</title></head>\r\n" +
                    "<body>" +
                    "<h1>Bad Request</h1>" +
                    "<p>HTTP request to HTTPS port</p>" +
                    "</body>\r\n" +
                    "</html>";
            String response =
                "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    body;
            Callback.Completable completable = new Callback.Completable();
            endPoint.write(completable, ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
            completable.whenComplete((r, x) -> endPoint.close());
        }
        else
        {
            endPoint.close();
        }
    }
}
