package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final ConcurrentMap<QuicheConnectionId, QuicSession> sessions = new ConcurrentHashMap<>();
    private final Connector connector;
    private final QuicheConfig quicheConfig;

    public QuicConnection(Connector connector, ServerDatagramEndPoint endp)
    {
        super(endp, connector.getExecutor());
        this.connector = connector;

        File[] files;
        try
        {
            SSLKeyPair keyPair;
            keyPair = new SSLKeyPair(new File("src/test/resources/keystore.p12"), "PKCS12", "storepwd".toCharArray(), "mykey", "storepwd".toCharArray());
            files = keyPair.export(new File(System.getProperty("java.io.tmpdir")));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // TODO make the QuicheConfig configurable
        quicheConfig = new QuicheConfig();
        quicheConfig.setPrivKeyPemPath(files[0].getPath());
        quicheConfig.setCertChainPemPath(files[1].getPath());
        quicheConfig.setVerifyPeer(false);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.RENO);
        quicheConfig.setApplicationProtos(getProtocols().toArray(new String[0]));
    }

    private Collection<String> getProtocols()
    {
        // TODO get the protocols from the connector
        return Collections.singletonList("http/0.9");
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
            ByteBufferPool byteBufferPool = connector.getByteBufferPool();
            ByteBuffer cipherBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN + ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
            BufferUtil.flipToFill(cipherBuffer);
            while (true)
            {
                // Read data
                int fill = getEndPoint().fill(cipherBuffer);
                // TODO ServerDatagramEndPoint will never return -1 to fill b/c of UDP
                if (fill < 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    getEndPoint().shutdownOutput();
                    return;
                }
                if (fill == 0)
                {
                    byteBufferPool.release(cipherBuffer);
                    fillInterested();
                    return;
                }

                InetSocketAddress remoteAddress = ServerDatagramEndPoint.decodeInetSocketAddress(cipherBuffer);
                QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(cipherBuffer);
                if (quicheConnectionId == null)
                {
                    BufferUtil.clearToFill(cipherBuffer);
                    continue;
                }

                QuicSession session = sessions.get(quicheConnectionId);
                if (session == null)
                {
                    QuicheConnection quicheConnection = QuicheConnection.tryAccept(quicheConfig, remoteAddress, cipherBuffer);
                    if (quicheConnection == null)
                    {
                        ByteBuffer addressBuffer = createFlushableAddressBuffer(byteBufferPool, remoteAddress);

                        ByteBuffer negotiationBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                        BufferUtil.flipToFill(negotiationBuffer);
                        QuicheConnection.negotiate(remoteAddress, cipherBuffer, negotiationBuffer);
                        negotiationBuffer.flip();

                        getEndPoint().write((UnequivocalCallback)x ->
                        {
                            byteBufferPool.release(addressBuffer);
                            byteBufferPool.release(negotiationBuffer);
                        }, addressBuffer, negotiationBuffer);
                    }
                    else
                    {
                        LOG.info("Quic connection accepted");
                        sessions.putIfAbsent(quicheConnectionId, new QuicSession(quicheConnection));
                    }
                }
                else
                {
                    QuicheConnection quicheConnection = session.getQuicheConnection();
                    quicheConnection.feedCipherText(cipherBuffer);
                    cipherBuffer.clear();
                    quicheConnection.drainCipherText(cipherBuffer);
                    cipherBuffer.flip();
                    ByteBuffer addressBuffer = createFlushableAddressBuffer(byteBufferPool, remoteAddress);
                    getEndPoint().write((UnequivocalCallback)x -> byteBufferPool.release(addressBuffer), addressBuffer, cipherBuffer);

                    if (quicheConnection.isConnectionEstablished())
                    {
                        List<Long> readableStreamIds = quicheConnection.readableStreamIds();
                        LOG.debug("readable stream ids: {}", readableStreamIds);
                        List<Long> writableStreamIds = quicheConnection.writableStreamIds();
                        LOG.debug("writable stream ids: {}", writableStreamIds);

                        for (Long readableStreamId : readableStreamIds)
                        {
                            boolean writable = writableStreamIds.remove(readableStreamId);
                            QuicStreamEndPoint streamEndPoint = session.getOrCreateStreamEndPoint(connector, connector.getScheduler(), getEndPoint().getLocalAddress(), remoteAddress, readableStreamId);
                            if (LOG.isDebugEnabled())
                                LOG.debug("selected endpoint for read{} : {}", (writable ? " and write" : ""), streamEndPoint);
                            streamEndPoint.onSelected(remoteAddress, true, writable);
                        }
                        for (Long writableStreamId : writableStreamIds)
                        {
                            QuicStreamEndPoint streamEndPoint = session.getOrCreateStreamEndPoint(connector, connector.getScheduler(), getEndPoint().getLocalAddress(), remoteAddress, writableStreamId);
                            LOG.debug("selected endpoint for write : {}", streamEndPoint);
                            streamEndPoint.onSelected(remoteAddress, false, true);
                        }
                    }
                }
                BufferUtil.clearToFill(cipherBuffer);
            }
        }
        catch (Throwable x)
        {
            close();
        }
    }

    private static ByteBuffer createFlushableAddressBuffer(ByteBufferPool byteBufferPool, InetSocketAddress remoteAddress) throws IOException
    {
        ByteBuffer addressBuffer = byteBufferPool.acquire(ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
        BufferUtil.flipToFill(addressBuffer);
        ServerDatagramEndPoint.encodeInetSocketAddress(addressBuffer, remoteAddress);
        addressBuffer.flip();
        return addressBuffer;
    }

    private interface UnequivocalCallback extends Callback
    {
        @Override
        default void succeeded()
        {
            any(null);
        }

        @Override
        default void failed(Throwable x)
        {
            any(x);
        }

        void any(Throwable x);
    }

}
