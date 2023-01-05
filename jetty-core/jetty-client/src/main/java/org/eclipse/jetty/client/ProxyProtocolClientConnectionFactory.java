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

package org.eclipse.jetty.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>ClientConnectionFactory for the
 * <a href="http://www.haproxy.org/download/2.1/doc/proxy-protocol.txt">PROXY protocol</a>.</p>
 * <p>Use the {@link V1} or {@link V2} versions of this class to specify what version of the
 * PROXY protocol you want to use.</p>
 */
public abstract class ProxyProtocolClientConnectionFactory implements ClientConnectionFactory
{
    /**
     * A ClientConnectionFactory for the PROXY protocol version 1.
     */
    public static class V1 extends ProxyProtocolClientConnectionFactory
    {
        public V1(ClientConnectionFactory factory)
        {
            super(factory);
        }

        @Override
        protected ProxyProtocolConnection newProxyProtocolConnection(EndPoint endPoint, Map<String, Object> context)
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            Tag tag = (Tag)destination.getOrigin().getTag();
            if (tag == null)
            {
                SocketAddress local = endPoint.getLocalSocketAddress();
                InetSocketAddress inetLocal = local instanceof InetSocketAddress ? (InetSocketAddress)local : null;
                InetAddress localAddress = inetLocal == null ? null : inetLocal.getAddress();
                SocketAddress remote = endPoint.getRemoteSocketAddress();
                InetSocketAddress inetRemote = remote instanceof InetSocketAddress ? (InetSocketAddress)remote : null;
                InetAddress remoteAddress = inetRemote == null ? null : inetRemote.getAddress();
                String family = local == null || inetLocal == null ? "UNKNOWN" : localAddress instanceof Inet4Address ? "TCP4" : "TCP6";
                tag = new Tag(family,
                    localAddress == null ? null : localAddress.getHostAddress(),
                    inetLocal == null ? 0 : inetLocal.getPort(),
                    remoteAddress == null ? null : remoteAddress.getHostAddress(),
                    inetRemote == null ? 0 : inetRemote.getPort());
            }
            return new ProxyProtocolConnectionV1(endPoint, executor, getClientConnectionFactory(), context, tag);
        }

        /**
         * <p>PROXY protocol version 1 metadata holder to be used in conjunction
         * with {@link Request#tag(Object)}.</p>
         * <p>Instances of this class are associated to a destination so that
         * all connections of that destination will initiate the communication
         * with the PROXY protocol version 1 bytes specified by this metadata.</p>
         */
        public static class Tag implements ClientConnectionFactory.Decorator
        {
            /**
             * The PROXY V1 Tag typically used to "ping" the server.
             */
            public static final Tag UNKNOWN = new Tag("UNKNOWN", null, 0, null, 0);

            private final String family;
            private final String srcIP;
            private final int srcPort;
            private final String dstIP;
            private final int dstPort;

            /**
             * <p>Creates a Tag whose metadata will be derived from the underlying EndPoint.</p>
             */
            public Tag()
            {
                this(null, 0);
            }

            /**
             * <p>Creates a Tag with the given source metadata.</p>
             * <p>The destination metadata will be derived from the underlying EndPoint.</p>
             *
             * @param srcIP the source IP address
             * @param srcPort the source port
             */
            public Tag(String srcIP, int srcPort)
            {
                this(null, srcIP, srcPort, null, 0);
            }

            /**
             * <p>Creates a Tag with the given metadata.</p>
             * 
             * @param family the protocol family
             * @param srcIP the source IP address
             * @param srcPort the source port
             * @param dstIP the destination IP address
             * @param dstPort the destination port
             */
            public Tag(String family, String srcIP, int srcPort, String dstIP, int dstPort)
            {
                this.family = family;
                this.srcIP = srcIP;
                this.srcPort = srcPort;
                this.dstIP = dstIP;
                this.dstPort = dstPort;
            }

            public String getFamily()
            {
                return family;
            }

            public String getSourceAddress()
            {
                return srcIP;
            }

            public int getSourcePort()
            {
                return srcPort;
            }

            public String getDestinationAddress()
            {
                return dstIP;
            }

            public int getDestinationPort()
            {
                return dstPort;
            }

            @Override
            public ClientConnectionFactory apply(ClientConnectionFactory factory)
            {
                return new V1(factory);
            }

            @Override
            public boolean equals(Object obj)
            {
                if (this == obj)
                    return true;
                if (obj == null || getClass() != obj.getClass())
                    return false;
                Tag that = (Tag)obj;
                return Objects.equals(family, that.family) &&
                    Objects.equals(srcIP, that.srcIP) &&
                    srcPort == that.srcPort &&
                    Objects.equals(dstIP, that.dstIP) &&
                    dstPort == that.dstPort;
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(family, srcIP, srcPort, dstIP, dstPort);
            }
        }
    }

    /**
     * A ClientConnectionFactory for the PROXY protocol version 2.
     */
    public static class V2 extends ProxyProtocolClientConnectionFactory
    {
        public V2(ClientConnectionFactory factory)
        {
            super(factory);
        }

        @Override
        protected ProxyProtocolConnection newProxyProtocolConnection(EndPoint endPoint, Map<String, Object> context)
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            Tag tag = (Tag)destination.getOrigin().getTag();
            if (tag == null)
            {
                SocketAddress local = endPoint.getLocalSocketAddress();
                InetSocketAddress inetLocal = local instanceof InetSocketAddress ? (InetSocketAddress)local : null;
                InetAddress localAddress = inetLocal == null ? null : inetLocal.getAddress();
                SocketAddress remote = endPoint.getRemoteSocketAddress();
                InetSocketAddress inetRemote = remote instanceof InetSocketAddress ? (InetSocketAddress)remote : null;
                InetAddress remoteAddress = inetRemote == null ? null : inetRemote.getAddress();
                Tag.Family family = local == null || inetLocal == null ? Tag.Family.UNSPEC : localAddress instanceof Inet4Address ? Tag.Family.INET4 : Tag.Family.INET6;
                tag = new Tag(Tag.Command.PROXY,
                    family,
                    Tag.Protocol.STREAM,
                    localAddress == null ? null : localAddress.getHostAddress(),
                    inetLocal == null ? 0 : inetLocal.getPort(),
                    remoteAddress == null ? null : remoteAddress.getHostAddress(),
                    inetRemote == null ? 0 : inetRemote.getPort(),
                    null);
            }
            return new ProxyProtocolConnectionV2(endPoint, executor, getClientConnectionFactory(), context, tag);
        }

        /**
         * <p>PROXY protocol version 2 metadata holder to be used in conjunction
         * with {@link Request#tag(Object)}.</p>
         * <p>Instances of this class are associated to a destination so that
         * all connections of that destination will initiate the communication
         * with the PROXY protocol version 2 bytes specified by this metadata.</p>
         */
        public static class Tag implements ClientConnectionFactory.Decorator
        {
            /**
             * The PROXY V2 Tag typically used to "ping" the server.
             */
            public static final Tag LOCAL = new Tag(Command.LOCAL, Family.UNSPEC, Protocol.UNSPEC, null, 0, null, 0, null);

            private final Command command;
            private final Family family;
            private final Protocol protocol;
            private final String srcIP;
            private final int srcPort;
            private final String dstIP;
            private final int dstPort;
            private final List<TLV> tlvs;

            /**
             * <p>Creates a Tag whose metadata will be derived from the underlying EndPoint.</p>
             */
            public Tag()
            {
                this(null, 0);
            }

            /**
             * <p>Creates a Tag with the given source metadata.</p>
             * <p>The destination metadata will be derived from the underlying EndPoint.</p>
             *
             * @param srcIP the source IP address
             * @param srcPort the source port
             */
            public Tag(String srcIP, int srcPort)
            {
                this(Command.PROXY, null, Protocol.STREAM, srcIP, srcPort, null, 0, null);
            }

            /**
             * <p>Creates a Tag with the given source metadata and Type-Length-Value (TLV) objects.</p>
             * <p>The destination metadata will be derived from the underlying EndPoint.</p>
             *
             * @param srcIP the source IP address
             * @param srcPort the source port
             * @param tlvs the TLV objects
             */
            public Tag(String srcIP, int srcPort, List<TLV> tlvs)
            {
                this(Command.PROXY, null, Protocol.STREAM, srcIP, srcPort, null, 0, tlvs);
            }

            /**
             * <p>Creates a Tag with the given metadata.</p>
             *
             * @param command the LOCAL or PROXY command
             * @param family the protocol family
             * @param protocol the protocol type
             * @param srcIP the source IP address
             * @param srcPort the source port
             * @param dstIP the destination IP address
             * @param dstPort the destination port
             * @param tlvs the TLV objects
             */
            public Tag(Command command, Family family, Protocol protocol, String srcIP, int srcPort, String dstIP, int dstPort, List<TLV> tlvs)
            {
                this.command = command;
                this.family = family;
                this.protocol = protocol;
                this.srcIP = srcIP;
                this.srcPort = srcPort;
                this.dstIP = dstIP;
                this.dstPort = dstPort;
                this.tlvs = tlvs;
            }

            public Command getCommand()
            {
                return command;
            }

            public Family getFamily()
            {
                return family;
            }

            public Protocol getProtocol()
            {
                return protocol;
            }

            public String getSourceAddress()
            {
                return srcIP;
            }

            public int getSourcePort()
            {
                return srcPort;
            }

            public String getDestinationAddress()
            {
                return dstIP;
            }

            public int getDestinationPort()
            {
                return dstPort;
            }

            public List<TLV> getTLVs()
            {
                return tlvs;
            }

            @Override
            public ClientConnectionFactory apply(ClientConnectionFactory factory)
            {
                return new V2(factory);
            }

            @Override
            public boolean equals(Object obj)
            {
                if (this == obj)
                    return true;
                if (obj == null || getClass() != obj.getClass())
                    return false;
                Tag that = (Tag)obj;
                return command == that.command &&
                    family == that.family &&
                    protocol == that.protocol &&
                    Objects.equals(srcIP, that.srcIP) &&
                    srcPort == that.srcPort &&
                    Objects.equals(dstIP, that.dstIP) &&
                    dstPort == that.dstPort &&
                    Objects.equals(tlvs, that.tlvs);
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(command, family, protocol, srcIP, srcPort, dstIP, dstPort, tlvs);
            }

            public enum Command
            {
                LOCAL, PROXY
            }

            public enum Family
            {
                UNSPEC, INET4, INET6, UNIX
            }

            public enum Protocol
            {
                UNSPEC, STREAM, DGRAM
            }

            public static class TLV
            {
                private final int type;
                private final byte[] value;

                public TLV(int type, byte[] value)
                {
                    if (type < 0 || type > 255)
                        throw new IllegalArgumentException("Invalid type: " + type);
                    if (value != null && value.length > 65535)
                        throw new IllegalArgumentException("Invalid value length: " + value.length);
                    this.type = type;
                    this.value = Objects.requireNonNull(value);
                }

                public int getType()
                {
                    return type;
                }

                public byte[] getValue()
                {
                    return value;
                }

                @Override
                public boolean equals(Object obj)
                {
                    if (this == obj)
                        return true;
                    if (obj == null || getClass() != obj.getClass())
                        return false;
                    TLV that = (TLV)obj;
                    return type == that.type && Arrays.equals(value, that.value);
                }

                @Override
                public int hashCode()
                {
                    int result = Objects.hash(type);
                    result = 31 * result + Arrays.hashCode(value);
                    return result;
                }
            }
        }
    }

    private final ClientConnectionFactory factory;

    private ProxyProtocolClientConnectionFactory(ClientConnectionFactory factory)
    {
        this.factory = factory;
    }

    public ClientConnectionFactory getClientConnectionFactory()
    {
        return factory;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        ProxyProtocolConnection connection = newProxyProtocolConnection(endPoint, context);
        return customize(connection, context);
    }

    protected abstract ProxyProtocolConnection newProxyProtocolConnection(EndPoint endPoint, Map<String, Object> context);

    protected abstract static class ProxyProtocolConnection extends AbstractConnection implements Callback
    {
        protected static final Logger LOG = LoggerFactory.getLogger(ProxyProtocolConnection.class);

        private final ClientConnectionFactory factory;
        private final Map<String, Object> context;

        private ProxyProtocolConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory factory, Map<String, Object> context)
        {
            super(endPoint, executor);
            this.factory = factory;
            this.context = context;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            writePROXYBytes(getEndPoint(), this);
        }

        protected abstract void writePROXYBytes(EndPoint endPoint, Callback callback);

        @Override
        public void succeeded()
        {
            try
            {
                EndPoint endPoint = getEndPoint();
                Connection connection = factory.newConnection(endPoint, context);
                if (LOG.isDebugEnabled())
                    LOG.debug("Written PROXY line, upgrading to {}", connection);
                endPoint.upgrade(connection);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        @Override
        public void failed(Throwable x)
        {
            close();
            Promise<?> promise = (Promise<?>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            promise.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void onFillable()
        {
        }
    }

    private static class ProxyProtocolConnectionV1 extends ProxyProtocolConnection
    {
        private final V1.Tag tag;

        public ProxyProtocolConnectionV1(EndPoint endPoint, Executor executor, ClientConnectionFactory factory, Map<String, Object> context, V1.Tag tag)
        {
            super(endPoint, executor, factory, context);
            this.tag = tag;
        }

        @Override
        protected void writePROXYBytes(EndPoint endPoint, Callback callback)
        {
            try
            {
                SocketAddress local = endPoint.getLocalSocketAddress();
                InetSocketAddress inetLocal = local instanceof InetSocketAddress ? (InetSocketAddress)local : null;
                InetAddress localAddress = inetLocal == null ? null : inetLocal.getAddress();
                SocketAddress remote = endPoint.getRemoteSocketAddress();
                InetSocketAddress inetRemote = remote instanceof InetSocketAddress ? (InetSocketAddress)remote : null;
                InetAddress remoteAddress = inetRemote == null ? null : inetRemote.getAddress();
                String family = tag.getFamily();
                String srcIP = tag.getSourceAddress();
                int srcPort = tag.getSourcePort();
                String dstIP = tag.getDestinationAddress();
                int dstPort = tag.getDestinationPort();
                if (family == null)
                    family = local == null || inetLocal == null ? "UNKNOWN" : localAddress instanceof Inet4Address ? "TCP4" : "TCP6";
                family = family.toUpperCase(Locale.ENGLISH);
                boolean unknown = family.equals("UNKNOWN");
                StringBuilder builder = new StringBuilder(64);
                builder.append("PROXY ").append(family);
                if (!unknown)
                {
                    if (srcIP == null && localAddress != null)
                        srcIP = localAddress.getHostAddress();
                    builder.append(" ").append(srcIP);
                    if (dstIP == null && remoteAddress != null)
                        dstIP = remoteAddress.getHostAddress();
                    builder.append(" ").append(dstIP);
                    if (srcPort <= 0 && inetLocal != null)
                        srcPort = inetLocal.getPort();
                    builder.append(" ").append(srcPort);
                    if (dstPort <= 0 && inetRemote != null)
                        dstPort = inetRemote.getPort();
                    builder.append(" ").append(dstPort);
                }
                builder.append("\r\n");
                String line = builder.toString();
                if (LOG.isDebugEnabled())
                    LOG.debug("Writing PROXY bytes: {}", line.trim());
                ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.US_ASCII));
                endPoint.write(callback, buffer);
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }
    }

    private static class ProxyProtocolConnectionV2 extends ProxyProtocolConnection
    {
        private static final byte[] MAGIC = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

        private final V2.Tag tag;

        public ProxyProtocolConnectionV2(EndPoint endPoint, Executor executor, ClientConnectionFactory factory, Map<String, Object> context, V2.Tag tag)
        {
            super(endPoint, executor, factory, context);
            this.tag = tag;
        }

        @Override
        protected void writePROXYBytes(EndPoint endPoint, Callback callback)
        {
            try
            {
                int capacity = MAGIC.length;
                capacity += 1; // version and command
                capacity += 1; // family and protocol
                capacity += 2; // length
                capacity += 216; // max address length
                List<V2.Tag.TLV> tlvs = tag.getTLVs();
                int vectorsLength = tlvs == null ? 0 : tlvs.stream()
                    .mapToInt(tlv -> 1 + 2 + tlv.getValue().length)
                    .sum();
                capacity += vectorsLength;
                ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                buffer.put(MAGIC);
                V2.Tag.Command command = tag.getCommand();
                int versionAndCommand = (2 << 4) | (command.ordinal() & 0x0F);
                buffer.put((byte)versionAndCommand);
                V2.Tag.Family family = tag.getFamily();
                String srcAddr = tag.getSourceAddress();
                SocketAddress local = endPoint.getLocalSocketAddress();
                InetSocketAddress inetLocal = local instanceof InetSocketAddress ? (InetSocketAddress)local : null;
                InetAddress localAddress = inetLocal == null ? null : inetLocal.getAddress();
                if (srcAddr == null && localAddress != null)
                    srcAddr = localAddress.getHostAddress();
                int srcPort = tag.getSourcePort();
                if (srcPort <= 0 && inetLocal != null)
                    srcPort = inetLocal.getPort();
                if (family == null)
                    family = local == null || inetLocal == null ? V2.Tag.Family.UNSPEC : localAddress instanceof Inet4Address ? V2.Tag.Family.INET4 : V2.Tag.Family.INET6;
                V2.Tag.Protocol protocol = tag.getProtocol();
                if (protocol == null)
                    protocol = local == null ? V2.Tag.Protocol.UNSPEC : V2.Tag.Protocol.STREAM;
                int familyAndProtocol = (family.ordinal() << 4) | protocol.ordinal();
                buffer.put((byte)familyAndProtocol);
                int length = 0;
                switch (family)
                {
                    case UNSPEC:
                        break;
                    case INET4:
                        length = 12;
                        break;
                    case INET6:
                        length = 36;
                        break;
                    case UNIX:
                        length = 216;
                        break;
                    default:
                        throw new IllegalStateException();
                }
                length += vectorsLength;
                buffer.putShort((short)length);
                String dstAddr = tag.getDestinationAddress();
                SocketAddress remote = endPoint.getRemoteSocketAddress();
                InetSocketAddress inetRemote = remote instanceof InetSocketAddress ? (InetSocketAddress)remote : null;
                InetAddress remoteAddress = inetRemote == null ? null : inetRemote.getAddress();
                if (dstAddr == null && remoteAddress != null)
                    dstAddr = remoteAddress.getHostAddress();
                int dstPort = tag.getDestinationPort();
                if (dstPort <= 0 && inetRemote != null)
                    dstPort = inetRemote.getPort();
                switch (family)
                {
                    case UNSPEC:
                        break;
                    case INET4:
                    case INET6:
                        buffer.put(InetAddress.getByName(srcAddr).getAddress());
                        buffer.put(InetAddress.getByName(dstAddr).getAddress());
                        buffer.putShort((short)srcPort);
                        buffer.putShort((short)dstPort);
                        break;
                    case UNIX:
                        int position = buffer.position();
                        if (srcAddr != null)
                            buffer.put(srcAddr.getBytes(StandardCharsets.US_ASCII));
                        position = position + 108;
                        buffer.position(position);
                        if (dstAddr != null)
                            buffer.put(dstAddr.getBytes(StandardCharsets.US_ASCII));
                        position = position + 108;
                        buffer.position(position);
                        break;
                    default:
                        throw new IllegalStateException();
                }
                if (tlvs != null)
                {
                    for (V2.Tag.TLV tlv : tlvs)
                    {
                        buffer.put((byte)tlv.getType());
                        byte[] data = tlv.getValue();
                        buffer.putShort((short)data.length);
                        buffer.put(data);
                    }
                }
                buffer.flip();
                endPoint.write(callback, buffer);
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }
    }
}
