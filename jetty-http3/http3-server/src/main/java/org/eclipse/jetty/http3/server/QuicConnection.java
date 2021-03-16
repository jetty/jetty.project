package org.eclipse.jetty.http3.server;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final ByteBufferPool byteBufferPool;
    private final ConcurrentMap<QuicheConnectionId, QuicheConnection> connections = new ConcurrentHashMap<>();
    private final QuicheConfig quicheConfig;

    public QuicConnection(ByteBufferPool byteBufferPool, Executor executor, ServerDatagramEndPoint endp)
    {
        super(endp, executor);
        this.byteBufferPool = byteBufferPool;

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
                QuicheConnection quicheConnection = connections.get(quicheConnectionId);
                if (quicheConnection == null)
                {
                    quicheConnection = QuicheConnection.tryAccept(quicheConfig, remoteAddress, cipherBuffer);
                    if (quicheConnection == null)
                    {
                        ByteBuffer addressBuffer = byteBufferPool.acquire(ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
                        BufferUtil.flipToFill(addressBuffer);
                        ServerDatagramEndPoint.encodeInetSocketAddress(addressBuffer, remoteAddress);
                        addressBuffer.flip();

                        ByteBuffer negotiationBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                        BufferUtil.flipToFill(negotiationBuffer);
                        QuicheConnection.negotiate(remoteAddress, cipherBuffer, negotiationBuffer);
                        negotiationBuffer.flip();

                        getEndPoint().write(new Callback() {
                            @Override
                            public void succeeded()
                            {
                                byteBufferPool.release(addressBuffer);
                                byteBufferPool.release(negotiationBuffer);
                            }

                            @Override
                            public void failed(Throwable x)
                            {
                                byteBufferPool.release(addressBuffer);
                                byteBufferPool.release(negotiationBuffer);
                            }
                        }, addressBuffer, negotiationBuffer);
                    }
                    else
                    {
                        LOG.info("Quic connection accepted");
                        connections.put(quicheConnectionId, quicheConnection);
                    }
                }
                else
                {
                    quicheConnection.feedCipherText(cipherBuffer);
                }
                BufferUtil.clearToFill(cipherBuffer);
            }
        }
        catch (Throwable x)
        {
            close();
        }
    }
}
