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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>ConnectionFactory for the PROXY Protocol.</p>
 * <p>This factory can be placed in front of any other connection factory
 * to process the proxy v1 or v2 line before the normal protocol handling</p>
 *
 * @see <a href="https://www.haproxy.org/download/2.8/doc/proxy-protocol.txt">PROXY protocol</a>
 */
public class ProxyConnectionFactory extends DetectorConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(ProxyConnectionFactory.class);

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

    private static class ProxyV1ConnectionFactory extends AbstractConnectionFactory implements ConnectionFactory.Detecting
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
            private final RetainableByteBuffer _buffer;
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
                    LOG.debug("Proxy v1 onFillable current index = {}", _index);
                try
                {
                    while (_index < LF_INDEX)
                    {
                        // Read data
                        int fill = getEndPoint().fill(_buffer.getByteBuffer());
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v1 filled buffer with {} bytes", fill);
                        if (fill < 0)
                        {
                            _buffer.release();
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
                    LOG.warn("Proxy v1 error for {} {}", getEndPoint(), x.toString());
                    if (LOG.isDebugEnabled())
                        LOG.warn("Proxy v1 error", x);
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
                    LOG.warn("Proxy v1 error for {} {}", getEndPoint(), x.toString());
                    if (LOG.isDebugEnabled())
                        LOG.warn("Proxy v1 error", x);
                    releaseAndClose();
                }
            }

            @Override
            public ByteBuffer onUpgradeFrom()
            {
                if (_buffer.hasRemaining())
                {
                    ByteBuffer unconsumed = ByteBuffer.allocateDirect(_buffer.remaining());
                    unconsumed.put(_buffer.getByteBuffer());
                    unconsumed.flip();
                    _buffer.release();
                    return unconsumed;
                }
                _buffer.release();
                return null;
            }

            @Override
            public void onUpgradeTo(ByteBuffer buffer)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 copying unconsumed buffer {}", BufferUtil.toDetailString(buffer));
                BufferUtil.append(_buffer.getByteBuffer(), buffer);
            }

            /**
             * @return true when parsing is done, false when more bytes are needed.
             */
            private boolean parse() throws IOException
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 parsing {}", _buffer);
                _length += _buffer.remaining();

                // Parse fields
                while (_buffer.hasRemaining())
                {
                    byte b = _buffer.getByteBuffer().get();
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
                _buffer.release();
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
                EndPoint proxyEndPoint;
                if (unknown)
                {
                    EndPoint endPoint = getEndPoint();
                    proxyEndPoint = new ProxyEndPoint(endPoint, endPoint.getLocalSocketAddress(), endPoint.getRemoteSocketAddress());
                }
                else
                {
                    SocketAddress remote = new InetSocketAddress(srcIP, Integer.parseInt(srcPort));
                    SocketAddress local = new InetSocketAddress(dstIP, Integer.parseInt(dstPort));
                    proxyEndPoint = new ProxyEndPoint(getEndPoint(), local, remote);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v1 next protocol '{}' for {} -> {}", _next, getEndPoint(), proxyEndPoint);

                upgradeToConnectionFactory(_next, _connector, proxyEndPoint);
            }
        }
    }

    private static class ProxyV2ConnectionFactory extends AbstractConnectionFactory implements ConnectionFactory.Detecting
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

        private static final int PP2_TYPE_ALPN = 0x01;
        private static final int PP2_TYPE_AUTHORITY = 0x02;
        private static final int PP2_TYPE_CRC32C = 0x03;
        private static final int PP2_TYPE_NOOP = 0x04;
        private static final int PP2_TYPE_UNIQUE_ID = 0x05;
        private static final int PP2_TYPE_SSL = 0x20;
        private static final int PP2_SUBTYPE_SSL_VERSION = 0x21;
        private static final int PP2_SUBTYPE_SSL_CN = 0x22;
        private static final int PP2_SUBTYPE_SSL_CIPHER = 0x23;
        private static final int PP2_SUBTYPE_SSL_SIG_ALG = 0x24;
        private static final int PP2_SUBTYPE_SSL_KEY_ALG = 0x25;
        private static final int PP2_TYPE_NETNS = 0x30;

        private static final int PP2_CLIENT_SSL = 0x01;
        private static final int PP2_CLIENT_CERT_CONN = 0x02;
        private static final int PP2_CLIENT_CERT_SESS = 0x04;

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
            private final RetainableByteBuffer _buffer;
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
                BufferUtil.append(_buffer.getByteBuffer(), buffer);
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
                    LOG.warn("Proxy v2 error for {} {}", getEndPoint(), x.toString());
                    if (LOG.isDebugEnabled())
                        LOG.warn("Proxy v2 error", x);
                    releaseAndClose();
                }
            }

            @Override
            public void onFillable()
            {
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v2 onFillable header parsed? {}", _headerParsed);
                    while (!_headerParsed)
                    {
                        // Read data
                        int fill = getEndPoint().fill(_buffer.getByteBuffer());
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v2 filled buffer with {} bytes", fill);
                        if (fill < 0)
                        {
                            _buffer.release();
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
                        LOG.debug("Proxy v2 onFillable header parsed, length = {}, buffer = {}", _length, _buffer);

                    while (_buffer.remaining() < _length)
                    {
                        // Read data
                        int fill = getEndPoint().fill(_buffer.getByteBuffer());
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proxy v2 filled buffer with {} bytes", fill);
                        if (fill < 0)
                        {
                            _buffer.release();
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
                    LOG.warn("Proxy v2 error for {} {}", getEndPoint(), x.toString());
                    if (LOG.isDebugEnabled())
                        LOG.warn("Proxy v2 error", x);
                    releaseAndClose();
                }
            }

            @Override
            public ByteBuffer onUpgradeFrom()
            {
                if (_buffer.hasRemaining())
                {
                    ByteBuffer unconsumed = ByteBuffer.allocateDirect(_buffer.remaining());
                    unconsumed.put(_buffer.getByteBuffer());
                    unconsumed.flip();
                    _buffer.release();
                    return unconsumed;
                }
                _buffer.release();
                return null;
            }

            private void parseBodyAndUpgrade() throws IOException
            {
                int nonProxyRemaining = _buffer.remaining() - _length;
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 parsing body, length = {}, buffer = {}", _length, _buffer);

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 body {} from {} for {}", _next, _buffer, this);

                // Do we need to wrap the endpoint?
                ByteBuffer byteBuffer = _buffer.getByteBuffer();
                ProxyEndPoint proxyEndPoint;
                EndPoint endPoint = getEndPoint();
                if (_local)
                {
                    byteBuffer.position(byteBuffer.position() + _length);
                    proxyEndPoint = new ProxyEndPoint(endPoint, endPoint.getLocalSocketAddress(), endPoint.getRemoteSocketAddress());
                }
                else
                {
                    SocketAddress local;
                    SocketAddress remote;
                    switch (_family)
                    {
                        case INET ->
                        {
                            byte[] addr = new byte[4];
                            byteBuffer.get(addr);
                            InetAddress srcAddr = Inet4Address.getByAddress(addr);
                            byteBuffer.get(addr);
                            InetAddress dstAddr = Inet4Address.getByAddress(addr);
                            int srcPort = byteBuffer.getChar();
                            int dstPort = byteBuffer.getChar();
                            local = new InetSocketAddress(dstAddr, dstPort);
                            remote = new InetSocketAddress(srcAddr, srcPort);
                        }
                        case INET6 ->
                        {
                            byte[] addr = new byte[16];
                            byteBuffer.get(addr);
                            InetAddress srcAddr = Inet6Address.getByAddress(addr);
                            byteBuffer.get(addr);
                            InetAddress dstAddr = Inet6Address.getByAddress(addr);
                            int srcPort = byteBuffer.getChar();
                            int dstPort = byteBuffer.getChar();
                            local = new InetSocketAddress(dstAddr, dstPort);
                            remote = new InetSocketAddress(srcAddr, srcPort);
                        }
                        case UNIX ->
                        {
                            byte[] addr = new byte[108];
                            byteBuffer.get(addr);
                            String src = UnixDomain.toPath(addr);
                            byteBuffer.get(addr);
                            String dst = UnixDomain.toPath(addr);
                            local = UnixDomain.newSocketAddress(dst);
                            remote = UnixDomain.newSocketAddress(src);
                        }
                        default -> throw new IllegalStateException("Unsupported family " + _family);
                    }
                    proxyEndPoint = new ProxyEndPoint(endPoint, local, remote);

                    // Any additional info?
                    while (byteBuffer.remaining() > nonProxyRemaining)
                    {
                        int type = 0xff & byteBuffer.get();
                        int length = byteBuffer.getChar();
                        byte[] value = new byte[length];
                        byteBuffer.get(value);

                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("Proxy v2 T=%x L=%d V=%s for %s", type, length, StringUtil.toHexString(value), this));

                        // PP2_TYPE_NOOP is only used for byte alignment, skip them.
                        if (type != PP2_TYPE_NOOP)
                            proxyEndPoint.putTLV(type, value);

                        if (type == PP2_TYPE_SSL)
                        {
                            int client = value[0] & 0xFF;
                            boolean verified = value[1] == 0 && value[2] == 0 && value[3] == 0 && value[4] == 0;
                            String version = null;
                            String distinguishedName = null;
                            String cipher = null;
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
                                    case PP2_SUBTYPE_SSL_VERSION -> version = new String(subValue, StandardCharsets.US_ASCII);
                                    case PP2_SUBTYPE_SSL_CN -> distinguishedName = new String(subValue, StandardCharsets.UTF_8);
                                    case PP2_SUBTYPE_SSL_CIPHER -> cipher = new String(subValue, StandardCharsets.US_ASCII);
                                }
                            }

                            // TODO should client certificate information be passed? For now we just check there is a client value
                            //      and the certificate has been verified
                            if (client != 0 && verified)
                                proxyEndPoint.setSecure(version, distinguishedName, cipher);
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("Proxy v2 {} {}", endPoint, proxyEndPoint);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 parsing dynamic packet part is now done, upgrading to {}", _nextProtocol);
                upgradeToConnectionFactory(_next, _connector, proxyEndPoint);
            }

            private void parseHeader() throws IOException
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 parsing fixed length packet part, buffer = {}", _buffer);
                if (_buffer.remaining() < HEADER_LENGTH)
                    return;

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 header {} for {}", _buffer, this);

                ByteBuffer byteBuffer = _buffer.getByteBuffer();

                // struct proxy_hdr_v2 {
                //     uint8_t sig[12];  /* hex 0D 0A 0D 0A 00 0D 0A 51 55 49 54 0A */
                //     uint8_t ver_cmd;  /* protocol version and command */
                //     uint8_t fam;      /* protocol family and address */
                //     uint16_t len;     /* number of following bytes part of the header */
                // };
                for (byte signatureByte : SIGNATURE)
                {
                    if (byteBuffer.get() != signatureByte)
                        throw new IOException("Proxy v2 bad PROXY signature");
                }

                int versionAndCommand = 0xFF & byteBuffer.get();
                if ((versionAndCommand & 0xF0) != 0x20)
                    throw new IOException("Proxy v2 bad PROXY version");
                _local = (versionAndCommand & 0xF) == 0x00;

                int transportAndFamily = 0xFF & byteBuffer.get();
                switch (transportAndFamily >> 4)
                {
                    case 0 -> _family = Family.UNSPEC;
                    case 1 -> _family = Family.INET;
                    case 2 -> _family = Family.INET6;
                    case 3 -> _family = Family.UNIX;
                    default -> throw new IOException("Proxy v2 bad PROXY family");
                }

                Transport transport = switch (transportAndFamily & 0xF)
                {
                    case 0 -> Transport.UNSPEC;
                    case 1 -> Transport.STREAM;
                    case 2 -> Transport.DGRAM;
                    default -> throw new IOException("Proxy v2 bad PROXY family");
                };

                _length = byteBuffer.getChar();

                if (!_local && (_family == Family.UNSPEC || transport != Transport.STREAM))
                    throw new IOException(String.format("Proxy v2 unsupported PROXY mode 0x%x,0x%x", versionAndCommand, transportAndFamily));

                if (_length > getMaxProxyHeader())
                    throw new IOException(String.format("Proxy v2 Unsupported PROXY mode 0x%x,0x%x,0x%x", versionAndCommand, transportAndFamily, _length));

                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 fixed length packet part is now parsed");
                _headerParsed = true;
            }

            private void releaseAndClose()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Proxy v2 releasing buffer and closing");
                _buffer.release();
                close();
            }
        }
    }

    public static class ProxyEndPoint implements EndPoint, EndPoint.Wrapper, EndPoint.Securable
    {
        private final EndPoint _endPoint;
        private final SocketAddress _local;
        private final SocketAddress _remote;
        private Map<Integer, byte[]> _tlvs;
        private String _tlsVersion;
        private SslSessionData _sslSessionData;

        @Deprecated
        public ProxyEndPoint(EndPoint endPoint, InetSocketAddress remote, InetSocketAddress local)
        {
            this(endPoint, (SocketAddress)local, remote);
        }

        public ProxyEndPoint(EndPoint endPoint, SocketAddress local, SocketAddress remote)
        {
            _endPoint = endPoint;
            _local = local;
            _remote = remote;
        }

        @Override
        public SslSessionData getSslSessionData()
        {
            return _sslSessionData;
        }

        public void setSecure(String version, String distinguishedName, String cipher)
        {
            _tlsVersion = version; // TODO should this be part of the SslSessionData?
            // TODO should distinguishedName be part of the SslSessionData for SNI checks?
            _sslSessionData = new SslSessionData(null, null, cipher, null);
        }

        public String getTlsVersion()
        {
            return _tlsVersion;
        }

        public EndPoint unwrap()
        {
            return _endPoint;
        }
        
        /**
         * <p>Sets a TLV vector, see section 2.2.7 of the PROXY protocol specification.</p>
         * 
         * @param type the TLV type
         * @param value the TLV value
         */
        private void putTLV(int type, byte[] value)
        {
            if (_tlvs == null)
                _tlvs = new HashMap<>();
            _tlvs.put(type, value);
        }
        
        /**
         * <p>Gets a TLV vector, see section 2.2.7 of the PROXY protocol specification.</p>
         *
         * @param type the TLV type
         * @return the TLV value or null if not present.
         */
        public byte[] getTLV(int type)
        {
            return _tlvs != null ? _tlvs.get(type) : null;
        }

        @Override
        public void close(Throwable cause)
        {
            _endPoint.close(cause);
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            return _endPoint.fill(buffer);
        }

        @Override
        public void fillInterested(Callback callback) throws ReadPendingException
        {
            _endPoint.fillInterested(callback);
        }

        @Override
        public boolean flush(ByteBuffer... buffer) throws IOException
        {
            return _endPoint.flush(buffer);
        }

        @Override
        public Connection getConnection()
        {
            return _endPoint.getConnection();
        }

        @Override
        public void setConnection(Connection connection)
        {
            _endPoint.setConnection(connection);
        }

        @Override
        public long getCreatedTimeStamp()
        {
            return _endPoint.getCreatedTimeStamp();
        }

        @Override
        public long getIdleTimeout()
        {
            return _endPoint.getIdleTimeout();
        }

        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            _endPoint.setIdleTimeout(idleTimeout);
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            SocketAddress local = getLocalSocketAddress();
            if (local instanceof InetSocketAddress)
                return (InetSocketAddress)local;
            return null;
        }

        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return _local;
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            SocketAddress remote = getRemoteSocketAddress();
            if (remote instanceof InetSocketAddress)
                return (InetSocketAddress)remote;
            return null;
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return _remote;
        }

        @Override
        public Object getTransport()
        {
            return _endPoint.getTransport();
        }

        @Override
        public boolean isFillInterested()
        {
            return _endPoint.isFillInterested();
        }

        @Override
        public boolean isInputShutdown()
        {
            return _endPoint.isInputShutdown();
        }

        @Override
        public boolean isOpen()
        {
            return _endPoint.isOpen();
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _endPoint.isOutputShutdown();
        }

        @Override
        public void onClose(Throwable cause)
        {
            _endPoint.onClose(cause);
        }

        @Override
        public void onOpen()
        {
            _endPoint.onOpen();
        }

        @Override
        public void shutdownOutput()
        {
            _endPoint.shutdownOutput();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[remote=%s,local=%s,endpoint=%s]",
                getClass().getSimpleName(),
                hashCode(),
                _remote,
                _local,
                _endPoint);
        }

        @Override
        public boolean tryFillInterested(Callback callback)
        {
            return _endPoint.tryFillInterested(callback);
        }

        @Override
        public void upgrade(Connection newConnection)
        {
            _endPoint.upgrade(newConnection);
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            _endPoint.write(callback, buffers);
        }
    }

    private static class UnixDomain
    {
        private static final Class<?> unixDomainSocketAddress = probe();

        private static Class<?> probe()
        {
            try
            {
                return ClassLoader.getPlatformClassLoader().loadClass("java.net.UnixDomainSocketAddress");
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored", x);
                return null;
            }
        }

        private static SocketAddress newSocketAddress(String path)
        {
            try
            {
                if (unixDomainSocketAddress != null)
                    return (SocketAddress)unixDomainSocketAddress.getMethod("of", String.class).invoke(null, path);
                return null;
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored", x);
                return null;
            }
        }

        private static String toPath(byte[] bytes)
        {
            // Unix-Domain paths are zero-terminated.
            int i = 0;
            while (i < bytes.length)
            {
                if (bytes[i++] == 0)
                    break;
            }
            return new String(bytes, 0, i, StandardCharsets.US_ASCII).trim();
        }
    }
}
