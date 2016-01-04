//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.Iterator;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * ConnectionFactory for the PROXY Protocol.
 * <p>This factory can be placed in front of any other connection factory
 * to process the proxy line before the normal protocol handling</p>
 *
 * @see <a href="http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt">http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt</a>
 */
public class ProxyConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = Log.getLogger(ProxyConnectionFactory.class);
    private final String _next;

    /* ------------------------------------------------------------ */
    /** Proxy Connection Factory that uses the next ConnectionFactory
     * on the connector as the next protocol
     */
    public ProxyConnectionFactory()
    {
        super("proxy");
        _next=null;
    }

    public ProxyConnectionFactory(String nextProtocol)
    {
        super("proxy");
        _next=nextProtocol;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endp)
    {
        String next=_next;
        if (next==null)
        {
            for (Iterator<String> i = connector.getProtocols().iterator();i.hasNext();)
            {
                String p=i.next();
                if (getProtocol().equalsIgnoreCase(p))
                {
                    next=i.next();
                    break;
                }
            }
        }

        return new ProxyConnection(endp,connector,next);
    }

    public static class ProxyConnection extends AbstractConnection
    {
        // 0     1 2       3       4 5 6
        // 98765432109876543210987654321
        // PROXY P R.R.R.R L.L.L.L R Lrn

        private final int[] __size = {29,23,21,13,5,3,1};
        private final Connector _connector;
        private final String _next;
        private final StringBuilder _builder=new StringBuilder();
        private final String[] _field=new String[6];
        private int _fields;
        private int _length;

        protected ProxyConnection(EndPoint endp, Connector connector, String next)
        {
            super(endp,connector.getExecutor());
            _connector=connector;
            _next=next;
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
                ByteBuffer buffer=null;
                loop: while(true)
                {
                    // Create a buffer that will not read too much data
                    int size=Math.max(1,__size[_fields]-_builder.length());
                    if (buffer==null || buffer.capacity()!=size)
                        buffer=BufferUtil.allocate(size);
                    else
                        BufferUtil.clear(buffer);

                    // Read data
                    int fill=getEndPoint().fill(buffer);
                    if (fill<0)
                    {
                        getEndPoint().shutdownOutput();
                        return;
                    }
                    if (fill==0)
                    {
                        fillInterested();
                        return;
                    }

                    _length+=fill;
                    if (_length>=108)
                    {
                        LOG.warn("PROXY line too long {} for {}",_length,getEndPoint());
                        close();
                        return;
                    }

                    // parse fields
                    while (buffer.hasRemaining())
                    {
                        byte b = buffer.get();
                        if (_fields<6)
                        {
                            if (b==' ' || b=='\r' && _fields==5)
                            {
                                _field[_fields++]=_builder.toString();
                                _builder.setLength(0);
                            }
                            else if (b<' ')
                            {
                                LOG.warn("Bad character {} for {}",b&0xFF,getEndPoint());
                                close();
                                return;
                            }
                            else
                            {
                                _builder.append((char)b);
                            }
                        }
                        else
                        {
                            if (b=='\n')
                                break loop;

                            LOG.warn("Bad CRLF for {}",getEndPoint());
                            close();
                            return;
                        }
                    }
                }

                // Check proxy
                if (!"PROXY".equals(_field[0]))
                {
                    LOG.warn("Not PROXY protocol for {}",getEndPoint());
                    close();
                    return;
                }

                // Extract Addresses
                InetSocketAddress remote=new InetSocketAddress(_field[2],Integer.parseInt(_field[4]));
                InetSocketAddress local =new InetSocketAddress(_field[3],Integer.parseInt(_field[5]));

                // Create the next protocol
                ConnectionFactory connectionFactory = _connector.getConnectionFactory(_next);
                if (connectionFactory == null)
                {
                    LOG.info("Next protocol '{}' for {}",_next,getEndPoint());
                    close();
                    return;
                }

                EndPoint endPoint = new ProxyEndPoint(getEndPoint(),remote,local);
                Connection newConnection = connectionFactory.newConnection(_connector, endPoint);
                endPoint.upgrade(newConnection);
            }
            catch (Throwable x)
            {
                LOG.warn("PROXY error for "+getEndPoint(),x);
                close();
            }
        }
    }

    public static class ProxyEndPoint implements EndPoint
    {
        private final EndPoint _endp;
        private final InetSocketAddress _remote;
        private final InetSocketAddress _local;

        public ProxyEndPoint(EndPoint endp, InetSocketAddress remote, InetSocketAddress local)
        {
            _endp=endp;
            _remote=remote;
            _local=local;
        }

        @Override
        public boolean isOptimizedForDirectBuffers()
        {
            return _endp.isOptimizedForDirectBuffers();
        }

        public InetSocketAddress getLocalAddress()
        {
            return _local;
        }

        public InetSocketAddress getRemoteAddress()
        {
            return _remote;
        }

        public boolean isOpen()
        {
            return _endp.isOpen();
        }

        public long getCreatedTimeStamp()
        {
            return _endp.getCreatedTimeStamp();
        }

        public void shutdownOutput()
        {
            _endp.shutdownOutput();
        }

        public boolean isOutputShutdown()
        {
            return _endp.isOutputShutdown();
        }

        public boolean isInputShutdown()
        {
            return _endp.isInputShutdown();
        }

        public void close()
        {
            _endp.close();
        }

        public int fill(ByteBuffer buffer) throws IOException
        {
            return _endp.fill(buffer);
        }

        public boolean flush(ByteBuffer... buffer) throws IOException
        {
            return _endp.flush(buffer);
        }

        public Object getTransport()
        {
            return _endp.getTransport();
        }

        public long getIdleTimeout()
        {
            return _endp.getIdleTimeout();
        }

        public void setIdleTimeout(long idleTimeout)
        {
            _endp.setIdleTimeout(idleTimeout);
        }

        public void fillInterested(Callback callback) throws ReadPendingException
        {
            _endp.fillInterested(callback);
        }

        @Override
        public boolean isFillInterested()
        {
            return _endp.isFillInterested();
        }

        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            _endp.write(callback,buffers);
        }

        public Connection getConnection()
        {
            return _endp.getConnection();
        }

        public void setConnection(Connection connection)
        {
            _endp.setConnection(connection);
        }

        public void onOpen()
        {
            _endp.onOpen();
        }

        public void onClose()
        {
            _endp.onClose();
        }

        @Override
        public void upgrade(Connection newConnection)
        {
            _endp.upgrade(newConnection);
        }
    }
}
