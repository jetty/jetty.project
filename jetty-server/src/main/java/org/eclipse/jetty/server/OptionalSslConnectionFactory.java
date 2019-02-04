//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A ConnectionFactory whose connections detect whether the first bytes are
 * TLS bytes and upgrades to either a TLS connection or to another configurable
 * connection.</p>
 */
public class OptionalSslConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = Log.getLogger(OptionalSslConnection.class);
    private static final int TLS_ALERT_FRAME_TYPE = 0x15;
    private static final int TLS_HANDSHAKE_FRAME_TYPE = 0x16;
    private static final int TLS_MAJOR_VERSION = 3;

    private final SslConnectionFactory sslConnectionFactory;
    private final String otherProtocol;

    /**
     * <p>Creates a new ConnectionFactory whose connections can upgrade to TLS or another protocol.</p>
     * <p>If {@code otherProtocol} is {@code null}, and the first bytes are not TLS, then
     * {@link #otherProtocol(ByteBuffer, EndPoint)} is called.</p>
     *
     * @param sslConnectionFactory The SslConnectionFactory to use if the first bytes are TLS
     * @param otherProtocol        the protocol of the ConnectionFactory to use if the first bytes are not TLS,
     *                             or null to explicitly handle the non-TLS case
     */
    public OptionalSslConnectionFactory(SslConnectionFactory sslConnectionFactory, String otherProtocol)
    {
        super("ssl|other");
        this.sslConnectionFactory = sslConnectionFactory;
        this.otherProtocol = otherProtocol;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        return configure(new OptionalSslConnection(endPoint, connector), connector, endPoint);
    }

    /**
     * @param buffer The buffer with the first bytes of the connection
     * @return whether the bytes seem TLS bytes
     */
    protected boolean seemsTLS(ByteBuffer buffer)
    {
        int tlsFrameType = buffer.get(0) & 0xFF;
        int tlsMajorVersion = buffer.get(1) & 0xFF;
        return (tlsFrameType == TLS_HANDSHAKE_FRAME_TYPE || tlsFrameType == TLS_ALERT_FRAME_TYPE) && tlsMajorVersion == TLS_MAJOR_VERSION;
    }

    /**
     * <p>Callback method invoked when {@code otherProtocol} is {@code null}
     * and the first bytes are not TLS.</p>
     * <p>This typically happens when a client is trying to connect to a TLS
     * port using the {@code http} scheme (and not the {@code https} scheme).</p>
     *
     * @param buffer   The buffer with the first bytes of the connection
     * @param endPoint The connection EndPoint object
     * @see #seemsTLS(ByteBuffer)
     */
    protected void otherProtocol(ByteBuffer buffer, EndPoint endPoint)
    {
        // There are always at least 2 bytes.
        int byte1 = buffer.get(0) & 0xFF;
        int byte2 = buffer.get(1) & 0xFF;
        if (byte1 == 'G' && byte2 == 'E')
        {
            // Plain text HTTP to a HTTPS port,
            // write a minimal response.
            String body = "" +
                    "<!DOCTYPE html>\r\n" +
                    "<html>\r\n" +
                    "<head><title>Bad Request</title></head>\r\n" +
                    "<body>" +
                    "<h1>Bad Request</h1>" +
                    "<p>HTTP request to HTTPS port</p>" +
                    "</body>\r\n" +
                    "</html>";
            String response = "" +
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

    private class OptionalSslConnection extends AbstractConnection implements Connection.UpgradeFrom
    {
        private final Connector connector;
        private final ByteBuffer buffer;

        public OptionalSslConnection(EndPoint endPoint, Connector connector)
        {
            super(endPoint, connector.getExecutor());
            this.connector = connector;
            this.buffer = BufferUtil.allocateDirect(1536);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        @Override
        public void onFillable()
        {
            try
            {
                while (true)
                {
                    int filled = getEndPoint().fill(buffer);
                    if (filled > 0)
                    {
                        // Always have at least 2 bytes.
                        if (BufferUtil.length(buffer) >= 2)
                        {
                            upgrade(buffer);
                            break;
                        }
                    }
                    else if (filled == 0)
                    {
                        fillInterested();
                        break;
                    }
                    else
                    {
                        close();
                        break;
                    }
                }
            }
            catch (IOException x)
            {
                LOG.warn(x);
                close();
            }
        }

        @Override
        public ByteBuffer onUpgradeFrom()
        {
            return buffer;
        }

        private void upgrade(ByteBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Read {}", BufferUtil.toDetailString(buffer));

            EndPoint endPoint = getEndPoint();
            if (seemsTLS(buffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Detected TLS bytes, upgrading to {}", sslConnectionFactory);
                endPoint.upgrade(sslConnectionFactory.newConnection(connector, endPoint));
            }
            else
            {
                if (otherProtocol != null)
                {
                    ConnectionFactory connectionFactory = connector.getConnectionFactory(otherProtocol);
                    if (connectionFactory != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Detected non-TLS bytes, upgrading to {}", connectionFactory);
                        Connection next = connectionFactory.newConnection(connector, endPoint);
                        endPoint.upgrade(next);
                    }
                    else
                    {
                        LOG.warn("Missing {} {} in {}", otherProtocol, ConnectionFactory.class.getSimpleName(), connector);
                        close();
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Detected non-TLS bytes, but no other protocol to upgrade to");
                    otherProtocol(buffer, endPoint);
                }
            }
        }
    }
}
