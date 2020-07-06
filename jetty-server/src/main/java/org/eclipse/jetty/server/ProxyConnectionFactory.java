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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>ConnectionFactory for the PROXY Protocol.</p>
 * <p>This factory can be placed in front of any other connection factory
 * to process the proxy v1 or v2 line before the normal protocol handling</p>
 *
 * @see <a href="http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt">http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt</a>
 */
public class ProxyConnectionFactory extends DetectorConnectionFactory
{
    public static final String TLS_VERSION = "TLS_VERSION";
    private static final Logger LOG = Log.getLogger(ProxyConnectionFactory.class);

    public ProxyConnectionFactory()
    {
        this(null);
    }

    public ProxyConnectionFactory(String nextProtocol)
    {
        super(new ProxyV1ConnectionFactory(nextProtocol), new ProxyV2ConnectionFactory(nextProtocol));
    }

    private static ConnectionFactory findNextConnectionFactory(String nextProtocol, Connector connector, String currentProtocol, EndPoint endp)
    {
        currentProtocol = "[" + currentProtocol + "]";
        if (LOG.isDebugEnabled())
            LOG.debug("finding connection factory following {} for protocol {}", currentProtocol, nextProtocol);
        String nextProtocolToFind = nextProtocol;
        if (nextProtocol == null)
            nextProtocolToFind = AbstractConnectionFactory.findNextProtocol(connector, currentProtocol);
        if (nextProtocolToFind == null)
            throw new IllegalStateException("Cannot find protocol following '" + currentProtocol + "' in connector's protocol list " + connector.getProtocols() + " for " + endp);
        ConnectionFactory connectionFactory = connector.getConnectionFactory(nextProtocolToFind);
        if (connectionFactory == null)
            throw new IllegalStateException("Cannot find protocol '" + nextProtocol + "' in connector's protocol list " + connector.getProtocols() + " for " + endp);
        if (LOG.isDebugEnabled())
            LOG.debug("found next connection factory {} for protocol {}", connectionFactory, nextProtocol);
        return connectionFactory;
    }

    public int getMaxProxyHeader()
    {
        ProxyV2ConnectionFactory v2 = getBean(ProxyV2ConnectionFactory.class);
        return v2.getMaxProxyHeader();
    }

    public void setMaxProxyHeader(int maxProxyHeader)
    {
        ProxyV2ConnectionFactory v2 = getBean(ProxyV2ConnectionFactory.class);
        v2.setMaxProxyHeader(maxProxyHeader);
    }

    private static class ProxyV1ConnectionFactory extends AbstractConnectionFactory implements Detecting
    {
        private static final byte[] SIGNATURE = "PROXY".getBytes(StandardCharsets.US_ASCII);

        private final String _nextProtocol;

        private ProxyV1ConnectionFactory(String nextProtocol)
        {
            super("proxy");
            this._nextProtocol = nextProtocol;
        }

        @Override
        public Detection detect(ByteBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Proxy v1 attempting detection with {} bytes", buffer.remaining());
            if (buffer.remaining() < SIGNATURE.length)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 detection requires more bytes");
                return Detection.NEED_MORE_BYTES;
            }

            for (int i = 0; i < SIGNATURE.length; i++)
            {
                byte signatureByte = SIGNATURE[i];
                byte byteInBuffer = buffer.get(i);
                if (byteInBuffer != signatureByte)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v1 detection unsuccessful");
                    return Detection.NOT_RECOGNIZED;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Proxy v1 detection succeeded");
            return Detection.RECOGNIZED;
        }

        @Override
        public Connection newConnection(Connector connector, EndPoint endp)
        {
            ConnectionFactory nextConnectionFactory = findNextConnectionFactory(_nextProtocol, connector, getProtocol(), endp);
            return configure(new ProxyProtocolV1Connection(endp, connector, nextConnectionFactory), connector, endp);
        }

        private static class ProxyProtocolV1Connection extends AbstractConnection implements Connection.UpgradeFrom, Connection.UpgradeTo
        {
            // 0     1 2       3       4 5 6
            // 98765432109876543210987654321
            // PROXY P R.R.R.R L.L.L.L R Lrn
            private static final int CR_INDEX = 6;
            private static final int LF_INDEX = 7;

            private final Connector _connector;
            private final ConnectionFactory _next;
            private final ByteBuffer _buffer;
            private final StringBuilder _builder = new StringBuilder();
            private final String[] _fields = new String[6];
            private int _index;
            private int _length;

            private ProxyProtocolV1Connection(EndPoint endp, Connector connector, ConnectionFactory next)
            {
                super(endp, connector.getExecutor());
                _connector = connector;
                _next = next;
                _buffer = _connector.getByteBufferPool().acquire(getInputBufferSize(), true);
            }

            @Override
            public void onFillable()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 onFillable current index = ", _index);
                try
                {
                    while (_index < LF_INDEX)
                    {
                        // Read data
                        int fill = getEndPoint().fill(_buffer);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v1 filled buffer with {} bytes", fill);
                        if (fill < 0)
                        {
                            _connector.getByteBufferPool().release(_buffer);
                            getEndPoint().shutdownOutput();
                            return;
                        }
                        if (fill == 0)
                        {
                            fillInterested();
                            return;
                        }

                        if (parse())
                            break;
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v1 onFillable parsing done, now upgrading");
                    upgrade();
                }
                catch (Throwable x)
                {
                    LOG.warn("Proxy v1 error for {}", getEndPoint(), x);
                    releaseAndClose();
                }
            }

            @Override
            public void onOpen()
            {
                super.onOpen();

                try
                {
                    while (_index < LF_INDEX)
                    {
                        if (!parse())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Proxy v1 onOpen parsing ran out of bytes, marking as fillInterested");
                            fillInterested();
                            return;
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v1 onOpen parsing done, now upgrading");
                    upgrade();
                }
                catch (Throwable x)
                {
                    LOG.warn("Proxy v1 error for {}", getEndPoint(), x);
                    releaseAndClose();
                }
            }

            @Override
            public ByteBuffer onUpgradeFrom()
            {
                if (_buffer.hasRemaining())
                {
                    ByteBuffer unconsumed = ByteBuffer.allocateDirect(_buffer.remaining());
                    unconsumed.put(_buffer);
                    unconsumed.flip();
                    _connector.getByteBufferPool().release(_buffer);
                    return unconsumed;
                }
                return null;
            }

            @Override
            public void onUpgradeTo(ByteBuffer buffer)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 copying unconsumed buffer {}", BufferUtil.toDetailString(buffer));
                BufferUtil.append(_buffer, buffer);
            }

            /**
             * @return true when parsing is done, false when more bytes are needed.
             */
            private boolean parse() throws IOException
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 parsing {}", BufferUtil.toDetailString(_buffer));
                _length += _buffer.remaining();

                // Parse fields
                while (_buffer.hasRemaining())
                {
                    byte b = _buffer.get();
                    if (_index < CR_INDEX)
                    {
                        if (b == ' ' || b == '\r')
                        {
                            _fields[_index++] = _builder.toString();
                            _builder.setLength(0);
                            if (b == '\r')
                                _index = CR_INDEX;
                        }
                        else if (b < ' ')
                        {
                            throw new IOException("Proxy v1 bad character " + (b & 0xFF));
                        }
                        else
                        {
                            _builder.append((char)b);
                        }
                    }
                    else
                    {
                        if (b == '\n')
                        {
                            _index = LF_INDEX;
                            if (LOG.isDebugEnabled())
                                LOG.debug("Proxy v1 parsing is done");
                            return true;
                        }

                        throw new IOException("Proxy v1 bad CRLF " + (b & 0xFF));
                    }
                }

                // Not enough bytes.
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 parsing requires more bytes");
                return false;
            }

            private void releaseAndClose()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 releasing buffer and closing");
                _connector.getByteBufferPool().release(_buffer);
                close();
            }

            private void upgrade()
            {
                int proxyLineLength = _length - _buffer.remaining();
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 pre-upgrade packet length (including CRLF) is {}", proxyLineLength);
                if (proxyLineLength >= 110)
                {
                    LOG.warn("Proxy v1 PROXY line too long {} for {}", proxyLineLength, getEndPoint());
                    releaseAndClose();
                    return;
                }

                // Check proxy
                if (!"PROXY".equals(_fields[0]))
                {
                    LOG.warn("Proxy v1 not PROXY protocol for {}", getEndPoint());
                    releaseAndClose();
                    return;
                }

                String srcIP = _fields[2];
                String srcPort = _fields[4];
                String dstIP = _fields[3];
                String dstPort = _fields[5];
                // If UNKNOWN, we must ignore the information sent, so use the EndPoint's.
                boolean unknown = "UNKNOWN".equalsIgnoreCase(_fields[1]);
                if (unknown)
                {
                    srcIP = getEndPoint().getRemoteAddress().getAddress().getHostAddress();
                    srcPort = String.valueOf(getEndPoint().getRemoteAddress().getPort());
                    dstIP = getEndPoint().getLocalAddress().getAddress().getHostAddress();
                    dstPort = String.valueOf(getEndPoint().getLocalAddress().getPort());
                }
                InetSocketAddress remote = new InetSocketAddress(srcIP, Integer.parseInt(srcPort));
                InetSocketAddress local = new InetSocketAddress(dstIP, Integer.parseInt(dstPort));

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 next protocol '{}' for {} r={} l={}", _next, getEndPoint(), remote, local);

                EndPoint endPoint = new ProxyEndPoint(getEndPoint(), remote, local);
                upgradeToConnectionFactory(_next, _connector, endPoint);
            }
        }
    }

    private static class ProxyV2ConnectionFactory extends AbstractConnectionFactory implements Detecting
    {
        private enum Family
        {
            UNSPEC, INET, INET6, UNIX
        }

        private enum Transport
        {
            UNSPEC, STREAM, DGRAM
        }

        private static final byte[] SIGNATURE = new byte[]
        {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
        };
        private final String _nextProtocol;
        private int _maxProxyHeader = 1024;

        private ProxyV2ConnectionFactory(String nextProtocol)
        {
            super("proxy");
            this._nextProtocol = nextProtocol;
        }

        @Override
        public Detection detect(ByteBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Proxy v2 attempting detection with {} bytes", buffer.remaining());
            if (buffer.remaining() < SIGNATURE.length)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 detection requires more bytes");
                return Detection.NEED_MORE_BYTES;
            }

            for (int i = 0; i < SIGNATURE.length; i++)
            {
                byte signatureByte = SIGNATURE[i];
                byte byteInBuffer = buffer.get(i);
                if (byteInBuffer != signatureByte)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v2 detection unsuccessful");
                    return Detection.NOT_RECOGNIZED;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Proxy v2 detection succeeded");
            return Detection.RECOGNIZED;
        }

        public int getMaxProxyHeader()
        {
            return _maxProxyHeader;
        }

        public void setMaxProxyHeader(int maxProxyHeader)
        {
            _maxProxyHeader = maxProxyHeader;
        }

        @Override
        public Connection newConnection(Connector connector, EndPoint endp)
        {
            ConnectionFactory nextConnectionFactory = findNextConnectionFactory(_nextProtocol, connector, getProtocol(), endp);
            return configure(new ProxyProtocolV2Connection(endp, connector, nextConnectionFactory), connector, endp);
        }

        private class ProxyProtocolV2Connection extends AbstractConnection implements Connection.UpgradeFrom, Connection.UpgradeTo
        {
            private static final int HEADER_LENGTH = 16;

            private final Connector _connector;
            private final ConnectionFactory _next;
            private final ByteBuffer _buffer;
            private boolean _local;
            private Family _family;
            private int _length;
            private boolean _headerParsed;

            protected ProxyProtocolV2Connection(EndPoint endp, Connector connector, ConnectionFactory next)
            {
                super(endp, connector.getExecutor());
                _connector = connector;
                _next = next;
                _buffer = _connector.getByteBufferPool().acquire(getInputBufferSize(), true);
            }

            @Override
            public void onUpgradeTo(ByteBuffer buffer)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 copying unconsumed buffer {}", BufferUtil.toDetailString(buffer));
                BufferUtil.append(_buffer, buffer);
            }

            @Override
            public void onOpen()
            {
                super.onOpen();

                try
                {
                    parseHeader();
                    if (_headerParsed && _buffer.remaining() >= _length)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v2 onOpen parsing fixed length packet part done, now upgrading");
                        parseBodyAndUpgrade();
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v2 onOpen parsing fixed length packet ran out of bytes, marking as fillInterested");
                        fillInterested();
                    }
                }
                catch (Exception x)
                {
                    LOG.warn("Proxy v2 error for {}", getEndPoint(), x);
                    releaseAndClose();
                }
            }

            @Override
            public void onFillable()
            {
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v2 onFillable header parsed? ", _headerParsed);
                    while (!_headerParsed)
                    {
                        // Read data
                        int fill = getEndPoint().fill(_buffer);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v2 filled buffer with {} bytes", fill);
                        if (fill < 0)
                        {
                            _connector.getByteBufferPool().release(_buffer);
                            getEndPoint().shutdownOutput();
                            return;
                        }
                        if (fill == 0)
                        {
                            fillInterested();
                            return;
                        }

                        parseHeader();
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v2 onFillable header parsed, length = {}, buffer = {}", _length, BufferUtil.toDetailString(_buffer));

                    while (_buffer.remaining() < _length)
                    {
                        // Read data
                        int fill = getEndPoint().fill(_buffer);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v2 filled buffer with {} bytes", fill);
                        if (fill < 0)
                        {
                            _connector.getByteBufferPool().release(_buffer);
                            getEndPoint().shutdownOutput();
                            return;
                        }
                        if (fill == 0)
                        {
                            fillInterested();
                            return;
                        }
                    }

                    parseBodyAndUpgrade();
                }
                catch (Throwable x)
                {
                    LOG.warn("Proxy v2 error for " + getEndPoint(), x);
                    releaseAndClose();
                }
            }

            @Override
            public ByteBuffer onUpgradeFrom()
            {
                if (_buffer.hasRemaining())
                {
                    ByteBuffer unconsumed = ByteBuffer.allocateDirect(_buffer.remaining());
                    unconsumed.put(_buffer);
                    unconsumed.flip();
                    _connector.getByteBufferPool().release(_buffer);
                    return unconsumed;
                }
                return null;
            }

            private void parseBodyAndUpgrade() throws IOException
            {
                // stop reading when bufferRemainingReserve bytes are remaining in the buffer
                int nonProxyRemaining = _buffer.remaining() - _length;
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 parsing body, length = {}, buffer = {}", _length, BufferUtil.toHexSummary(_buffer));

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 body {} from {} for {}", _next, BufferUtil.toHexSummary(_buffer), this);

                // Do we need to wrap the endpoint?
                EndPoint endPoint = getEndPoint();
                if (!_local)
                {
                    InetAddress src;
                    InetAddress dst;
                    int sp;
                    int dp;

                    switch (_family)
                    {
                        case INET:
                        {
                            byte[] addr = new byte[4];
                            _buffer.get(addr);
                            src = Inet4Address.getByAddress(addr);
                            _buffer.get(addr);
                            dst = Inet4Address.getByAddress(addr);
                            sp = _buffer.getChar();
                            dp = _buffer.getChar();
                            break;
                        }

                        case INET6:
                        {
                            byte[] addr = new byte[16];
                            _buffer.get(addr);
                            src = Inet6Address.getByAddress(addr);
                            _buffer.get(addr);
                            dst = Inet6Address.getByAddress(addr);
                            sp = _buffer.getChar();
                            dp = _buffer.getChar();
                            break;
                        }

                        default:
                            throw new IllegalStateException();
                    }

                    // Extract Addresses
                    InetSocketAddress remote = new InetSocketAddress(src, sp);
                    InetSocketAddress local = new InetSocketAddress(dst, dp);
                    ProxyEndPoint proxyEndPoint = new ProxyEndPoint(endPoint, remote, local);
                    endPoint = proxyEndPoint;

                    // Any additional info?
                    while (_buffer.remaining() > nonProxyRemaining)
                    {
                        int type = 0xff & _buffer.get();
                        int length = _buffer.getChar();
                        byte[] value = new byte[length];
                        _buffer.get(value);

                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("Proxy v2 T=%x L=%d V=%s for %s", type, length, TypeUtil.toHexString(value), this));

                        switch (type)
                        {
                            case 0x20: // PP2_TYPE_SSL
                            {
                                int client = value[0] & 0xFF;
                                switch (client)
                                {
                                    case 0x01: // PP2_CLIENT_SSL
                                    {
                                        int i = 5; // Index of the first sub_tlv, after verify.
                                        while (i < length)
                                        {
                                            int subType = value[i++] & 0xFF;
                                            int subLength = (value[i++] & 0xFF) * 256 + (value[i++] & 0xFF);
                                            byte[] subValue = new byte[subLength];
                                            System.arraycopy(value, i, subValue, 0, subLength);
                                            i += subLength;
                                            switch (subType)
                                            {
                                                case 0x21: // PP2_SUBTYPE_SSL_VERSION
                                                    String tlsVersion = new String(subValue, StandardCharsets.US_ASCII);
                                                    proxyEndPoint.setAttribute(TLS_VERSION, tlsVersion);
                                                    break;
                                                case 0x22: // PP2_SUBTYPE_SSL_CN
                                                case 0x23: // PP2_SUBTYPE_SSL_CIPHER
                                                case 0x24: // PP2_SUBTYPE_SSL_SIG_ALG
                                                case 0x25: // PP2_SUBTYPE_SSL_KEY_ALG
                                                default:
                                                    break;
                                            }
                                        }
                                        break;
                                    }
                                    case 0x02: // PP2_CLIENT_CERT_CONN
                                    case 0x04: // PP2_CLIENT_CERT_SESS
                                    default:
                                        break;
                                }
                                break;
                            }
                            case 0x01: // PP2_TYPE_ALPN
                            case 0x02: // PP2_TYPE_AUTHORITY
                            case 0x03: // PP2_TYPE_CRC32C
                            case 0x04: // PP2_TYPE_NOOP
                            case 0x30: // PP2_TYPE_NETNS
                            default:
                                break;
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v2 {} {}", getEndPoint(), proxyEndPoint.toString());
                }
                else
                {
                    _buffer.position(_buffer.position() + _length);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 parsing dynamic packet part is now done, upgrading to {}", _nextProtocol);
                upgradeToConnectionFactory(_next, _connector, endPoint);
            }

            private void parseHeader() throws IOException
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 parsing fixed length packet part, buffer = {}", BufferUtil.toDetailString(_buffer));
                if (_buffer.remaining() < HEADER_LENGTH)
                    return;

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 header {} for {}", BufferUtil.toHexSummary(_buffer), this);

                // struct proxy_hdr_v2 {
                //     uint8_t sig[12];  /* hex 0D 0A 0D 0A 00 0D 0A 51 55 49 54 0A */
                //     uint8_t ver_cmd;  /* protocol version and command */
                //     uint8_t fam;      /* protocol family and address */
                //     uint16_t len;     /* number of following bytes part of the header */
                // };
                for (byte signatureByte : SIGNATURE)
                {
                    if (_buffer.get() != signatureByte)
                        throw new IOException("Proxy v2 bad PROXY signature");
                }

                int versionAndCommand = 0xFF & _buffer.get();
                if ((versionAndCommand & 0xF0) != 0x20)
                    throw new IOException("Proxy v2 bad PROXY version");
                _local = (versionAndCommand & 0xF) == 0x00;

                int transportAndFamily = 0xFF & _buffer.get();
                switch (transportAndFamily >> 4)
                {
                    case 0:
                        _family = Family.UNSPEC;
                        break;
                    case 1:
                        _family = Family.INET;
                        break;
                    case 2:
                        _family = Family.INET6;
                        break;
                    case 3:
                        _family = Family.UNIX;
                        break;
                    default:
                        throw new IOException("Proxy v2 bad PROXY family");
                }

                Transport transport;
                switch (0xF & transportAndFamily)
                {
                    case 0:
                        transport = Transport.UNSPEC;
                        break;
                    case 1:
                        transport = Transport.STREAM;
                        break;
                    case 2:
                        transport = Transport.DGRAM;
                        break;
                    default:
                        throw new IOException("Proxy v2 bad PROXY family");
                }

                _length = _buffer.getChar();

                if (!_local && (_family == Family.UNSPEC || _family == Family.UNIX || transport != Transport.STREAM))
                    throw new IOException(String.format("Proxy v2 unsupported PROXY mode 0x%x,0x%x", versionAndCommand, transportAndFamily));

                if (_length > getMaxProxyHeader())
                    throw new IOException(String.format("Proxy v2 Unsupported PROXY mode 0x%x,0x%x,0x%x", versionAndCommand, transportAndFamily, _length));

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 fixed length packet part is now parsed");
                _headerParsed = true;
            }

            private void releaseAndClose()
            {
                _connector.getByteBufferPool().release(_buffer);
                close();
            }
        }
    }

    public static class ProxyEndPoint extends AttributesMap implements EndPoint
    {
        private final EndPoint _endp;
        private final InetSocketAddress _remote;
        private final InetSocketAddress _local;

        public ProxyEndPoint(EndPoint endp, InetSocketAddress remote, InetSocketAddress local)
        {
            _endp = endp;
            _remote = remote;
            _local = local;
        }

        public EndPoint unwrap()
        {
            return _endp;
        }

        @Override
        public void close()
        {
            _endp.close();
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            return _endp.fill(buffer);
        }

        @Override
        public void fillInterested(Callback callback) throws ReadPendingException
        {
            _endp.fillInterested(callback);
        }

        @Override
        public boolean flush(ByteBuffer... buffer) throws IOException
        {
            return _endp.flush(buffer);
        }

        @Override
        public Connection getConnection()
        {
            return _endp.getConnection();
        }

        @Override
        public void setConnection(Connection connection)
        {
            _endp.setConnection(connection);
        }

        @Override
        public long getCreatedTimeStamp()
        {
            return _endp.getCreatedTimeStamp();
        }

        @Override
        public long getIdleTimeout()
        {
            return _endp.getIdleTimeout();
        }

        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            _endp.setIdleTimeout(idleTimeout);
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return _local;
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return _remote;
        }

        @Override
        public Object getTransport()
        {
            return _endp.getTransport();
        }

        @Override
        public boolean isFillInterested()
        {
            return _endp.isFillInterested();
        }

        @Override
        public boolean isInputShutdown()
        {
            return _endp.isInputShutdown();
        }

        @Override
        public boolean isOpen()
        {
            return _endp.isOpen();
        }

        @Override
        public boolean isOptimizedForDirectBuffers()
        {
            return _endp.isOptimizedForDirectBuffers();
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _endp.isOutputShutdown();
        }

        @Override
        public void onClose()
        {
            _endp.onClose();
        }

        @Override
        public void onOpen()
        {
            _endp.onOpen();
        }

        @Override
        public void shutdownOutput()
        {
            _endp.shutdownOutput();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[remote=%s,local=%s,endpoint=%s]",
                getClass().getSimpleName(),
                hashCode(),
                _remote,
                _local,
                _endp);
        }

        @Override
        public boolean tryFillInterested(Callback callback)
        {
            return _endp.tryFillInterested(callback);
        }

        @Override
        public void upgrade(Connection newConnection)
        {
            _endp.upgrade(newConnection);
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            _endp.write(callback, buffers);
        }
    }
}
