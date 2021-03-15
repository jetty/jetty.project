package org.eclipse.jetty.http3.server;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
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
        return Arrays.asList("http/0.9");
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
            ByteBuffer buffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN + ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
            // Read data
            int fill = getEndPoint().fill(buffer);
            if (fill < 0)
            {
                byteBufferPool.release(buffer);
                getEndPoint().shutdownOutput();
                return;
            }
            if (fill == 0)
            {
                byteBufferPool.release(buffer);
                fillInterested();
                return;
            }

            InetSocketAddress remoteAddress = ServerDatagramEndPoint.decodeInetSocketAddress(buffer);
            QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(buffer);
            QuicheConnection quicheConnection = connections.get(quicheConnectionId);
            if (quicheConnection == null)
            {
                quicheConnection = QuicheConnection.tryAccept(quicheConfig, remoteAddress, buffer);
                if (quicheConnection == null)
                {
                    ByteBuffer address = byteBufferPool.acquire(ServerDatagramEndPoint.ENCODED_ADDRESS_LENGTH, true);
                    BufferUtil.flipToFill(address);
                    ServerDatagramEndPoint.encodeInetSocketAddress(address, remoteAddress);
                    address.flip();

                    ByteBuffer buffer2 = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                    BufferUtil.flipToFill(buffer2);
                    QuicheConnection.negotiate(remoteAddress, buffer, buffer2);

                    getEndPoint().flush(address, buffer2);
                    byteBufferPool.release(address);
                    byteBufferPool.release(buffer2);
                }
                else
                {
                    LOG.info("Quic connection accepted");
                    connections.put(quicheConnectionId, quicheConnection);
                }
            }
            else
            {
                quicheConnection.feedCipherText(buffer);
            }
            byteBufferPool.release(buffer);
            fillInterested();
        }
        catch (Throwable x)
        {
            close();
        }
    }
}
