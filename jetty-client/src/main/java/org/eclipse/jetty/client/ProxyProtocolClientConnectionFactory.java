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

package org.eclipse.jetty.client;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>ClientConnectionFactory for the
 * <a href="http://www.haproxy.org/download/2.1/doc/proxy-protocol.txt">PROXY protocol</a>.</p>
 * <p>Use the {@link V1} or {@link V2} versions of this class to specify what version of the
 * PROXY protocol you want to use.</p>
 */
public abstract class ProxyProtocolClientConnectionFactory implements ClientConnectionFactory
{
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
            Info info = (Info)destination.getOrigin().getTag();
            if (info == null)
            {
                InetSocketAddress local = endPoint.getLocalAddress();
                InetSocketAddress remote = endPoint.getRemoteAddress();
                boolean ipv6 = remote.getAddress() instanceof Inet6Address;
                info = new Info(ipv6 ? "TCP6" : "TCP4", local.getHostString(), local.getPort(), remote.getHostString(), remote.getPort());
            }
            return new ProxyProtocolConnectionV1(endPoint, executor, getClientConnectionFactory(), context, info);
        }

        public static class Info implements ClientConnectionFactory.Decorator
        {
            private final String family;
            private final String srcIP;
            private final int srcPort;
            private final String dstIP;
            private final int dstPort;

            public Info(String family, String srcIP, int srcPort, String dstIP, int dstPort)
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
                Info that = (Info)obj;
                return family.equals(that.family) &&
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
            V2.Info info = (V2.Info)destination.getOrigin().getTag();
            if (info == null)
            {
                InetSocketAddress local = endPoint.getLocalAddress();
                InetSocketAddress remote = endPoint.getRemoteAddress();
                boolean ipv6 = remote.getAddress() instanceof Inet6Address;
                info = new V2.Info(Info.Command.PROXY, ipv6 ? Info.Family.INET6 : Info.Family.INET4, Info.Protocol.STREAM, local.getHostString(), local.getPort(), remote.getHostString(), remote.getPort());
            }
            return new ProxyProtocolConnectionV2(endPoint, executor, getClientConnectionFactory(), context, info);
        }

        public static class Info implements ClientConnectionFactory.Decorator
        {
            private Command command;
            private Family family;
            private Protocol protocol;
            private String srcIP;
            private int srcPort;
            private String dstIP;
            private int dstPort;
            private Map<Integer, byte[]> vectors;

            public Info(Command command, Family family, Protocol protocol, String srcIP, int srcPort, String dstIP, int dstPort)
            {
                this.command = command;
                this.family = family;
                this.protocol = protocol;
                this.srcIP = srcIP;
                this.srcPort = srcPort;
                this.dstIP = dstIP;
                this.dstPort = dstPort;
            }

            public void put(int type, byte[] data)
            {
                if (type < 0 || type > 255)
                    throw new IllegalArgumentException("Invalid type: " + type);
                if (data != null && data.length > 65535)
                    throw new IllegalArgumentException("Invalid data length: " + data.length);
                if (vectors == null)
                    vectors = new HashMap<>();
                vectors.put(type, data);
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

            public Map<Integer, byte[]> getVectors()
            {
                return vectors != null ? vectors : Collections.emptyMap();
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
                Info that = (Info)obj;
                return command == that.command &&
                    family == that.family &&
                    protocol == that.protocol &&
                    Objects.equals(srcIP, that.srcIP) &&
                    srcPort == that.srcPort &&
                    Objects.equals(dstIP, that.dstIP) &&
                    dstPort == that.dstPort;
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(command, family, protocol, srcIP, srcPort, dstIP, dstPort);
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
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        ProxyProtocolConnection connection = newProxyProtocolConnection(endPoint, context);
        return customize(connection, context);
    }

    protected abstract ProxyProtocolConnection newProxyProtocolConnection(EndPoint endPoint, Map<String, Object> context);

    private abstract static class ProxyProtocolConnection extends AbstractConnection implements Callback
    {
        protected static final Logger LOG = Log.getLogger(ProxyProtocolConnection.class);

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
            writePROXYBytes(this);
        }

        protected abstract void writePROXYBytes(Callback callback);

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
        private final ProxyProtocolClientConnectionFactory.V1.Info info;

        public ProxyProtocolConnectionV1(EndPoint endPoint, Executor executor, ClientConnectionFactory factory, Map<String, Object> context, ProxyProtocolClientConnectionFactory.V1.Info info)
        {
            super(endPoint, executor, factory, context);
            this.info = info;
        }

        @Override
        protected void writePROXYBytes(Callback callback)
        {
            StringBuilder builder = new StringBuilder(64);
            builder.append("PROXY ").append(info.getFamily());
            String srcIP = info.getSourceAddress();
            if (srcIP != null)
                builder.append(" ").append(srcIP);
            String dstIP = info.getDestinationAddress();
            if (dstIP != null)
                builder.append(" ").append(dstIP);
            int srcPort = info.getSourcePort();
            if (srcPort > 0)
                builder.append(" ").append(srcPort);
            int dstPort = info.getDestinationPort();
            if (dstPort > 0)
                builder.append(" ").append(dstPort);
            builder.append("\r\n");
            String line = builder.toString();
            if (LOG.isDebugEnabled())
                LOG.debug("Writing PROXY bytes: {}", line.trim());
            ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.US_ASCII));
            getEndPoint().write(callback, buffer);
        }
    }

    private static class ProxyProtocolConnectionV2 extends ProxyProtocolConnection
    {
        private static final byte[] MAGIC = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

        private final ProxyProtocolClientConnectionFactory.V2.Info info;

        public ProxyProtocolConnectionV2(EndPoint endPoint, Executor executor, ClientConnectionFactory factory, Map<String, Object> context, ProxyProtocolClientConnectionFactory.V2.Info info)
        {
            super(endPoint, executor, factory, context);
            this.info = info;
        }

        @Override
        protected void writePROXYBytes(Callback callback)
        {
            try
            {
                int capacity = MAGIC.length;
                capacity += 1; // version and command
                capacity += 1; // family and protocol
                capacity += 2; // length
                int length = 0;
                V2.Info.Family family = info.getFamily();
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
                Map<Integer, byte[]> vectors = info.getVectors();
                length += vectors.values().stream()
                    .mapToInt(data -> 1 + 2 + data.length)
                    .sum();
                capacity += length;
                ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                buffer.put(MAGIC);
                int versionAndCommand = (2 << 4) | (info.getCommand().ordinal() & 0x0F);
                buffer.put((byte)versionAndCommand);
                int familyAndProtocol = (family.ordinal() << 4) | info.getProtocol().ordinal();
                buffer.put((byte)familyAndProtocol);
                buffer.putShort((short)length);
                switch (family)
                {
                    case UNSPEC:
                        break;
                    case INET4:
                    case INET6:
                        buffer.put(InetAddress.getByName(info.getSourceAddress()).getAddress());
                        buffer.put(InetAddress.getByName(info.getDestinationAddress()).getAddress());
                        buffer.putShort((short)info.getSourcePort());
                        buffer.putShort((short)info.getDestinationPort());
                        break;
                    case UNIX:
                        int position = buffer.position();
                        buffer.put(info.getSourceAddress().getBytes(StandardCharsets.US_ASCII));
                        buffer.position(position + 108);
                        buffer.put(info.getDestinationAddress().getBytes(StandardCharsets.US_ASCII));
                        break;
                    default:
                        throw new IllegalStateException();
                }
                for (Map.Entry<Integer, byte[]> entry : vectors.entrySet())
                {
                    buffer.put(entry.getKey().byteValue());
                    byte[] data = entry.getValue();
                    buffer.putShort((short)data.length);
                    buffer.put(data);
                }
                buffer.flip();
                getEndPoint().write(callback, buffer);
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }
    }
}
