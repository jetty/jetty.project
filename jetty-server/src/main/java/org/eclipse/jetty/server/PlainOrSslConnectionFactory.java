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

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A ConnectionFactory whose connections detect whether the first bytes are
 * TLS bytes and upgrades to either a TLS connection or to a plain connection.</p>
 */
public class PlainOrSslConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = Log.getLogger(PlainOrSslConnection.class);
    private static final int TLS_ALERT_FRAME_TYPE = 0x15;
    private static final int TLS_HANDSHAKE_FRAME_TYPE = 0x16;
    private static final int TLS_MAJOR_VERSION = 3;

    private final SslConnectionFactory sslConnectionFactory;
    private final String plainProtocol;

    /**
     * <p>Creates a new plain or TLS ConnectionFactory.</p>
     * <p>If {@code plainProtocol} is {@code null}, and the first bytes are not TLS, then
     * {@link #unknownProtocol(ByteBuffer, EndPoint)} is called; applications may override its
     * behavior (by default it closes the EndPoint) for example by writing a minimal response. </p>
     *
     * @param sslConnectionFactory The SslConnectionFactory to use if the first bytes are TLS
     * @param plainProtocol        the protocol of the ConnectionFactory to use if the first bytes are not TLS, or null.
     */
    public PlainOrSslConnectionFactory(SslConnectionFactory sslConnectionFactory, String plainProtocol)
    {
        super("plain|ssl");
        this.sslConnectionFactory = sslConnectionFactory;
        this.plainProtocol = plainProtocol;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        return configure(new PlainOrSslConnection(endPoint, connector), connector, endPoint);
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
     * <p>Callback method invoked when {@code plainProtocol} is {@code null}
     * and the first bytes are not TLS.</p>
     * <p>This typically happens when a client is trying to connect to a TLS
     * port using the {@code http} scheme (and not the {@code https} scheme).</p>
     * <p>This method may be overridden to write back a minimal response such as:</p>
     * <pre>
     * HTTP/1.1 400 Bad Request
     * Content-Length: 35
     * Content-Type: text/plain; charset=UTF8
     * Connection: close
     *
     * Plain HTTP request sent to TLS port
     * </pre>
     *
     * @param buffer   The buffer with the first bytes of the connection
     * @param endPoint The connection EndPoint object
     * @see #seemsTLS(ByteBuffer)
     */
    protected void unknownProtocol(ByteBuffer buffer, EndPoint endPoint)
    {
        endPoint.close();
    }

    private class PlainOrSslConnection extends AbstractConnection implements Connection.UpgradeFrom
    {
        private final Connector connector;
        private final ByteBuffer buffer;

        public PlainOrSslConnection(EndPoint endPoint, Connector connector)
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
                int filled = getEndPoint().fill(buffer);
                if (filled > 0)
                {
                    upgrade(buffer);
                }
                else if (filled == 0)
                {
                    fillInterested();
                }
                else
                {
                    close();
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
                if (plainProtocol != null)
                {
                    ConnectionFactory connectionFactory = connector.getConnectionFactory(plainProtocol);
                    if (connectionFactory != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Detected plain bytes, upgrading to {}", connectionFactory);
                        Connection next = connectionFactory.newConnection(connector, endPoint);
                        endPoint.upgrade(next);
                    }
                    else
                    {
                        LOG.warn("Missing {} {} in {}", plainProtocol, ConnectionFactory.class.getSimpleName(), connector);
                        close();
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Detected plain bytes, but no configured protocol to upgrade to");
                    unknownProtocol(buffer, endPoint);
                }
            }
        }
    }
}
