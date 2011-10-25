package org.eclipse.jetty.io.nio;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.BeforeClass;


public class SslSelectChannelEndPointTest extends SelectChannelEndPointTest
{
    static SslContextFactory __sslCtxFactory=new SslContextFactory();
    
    @BeforeClass
    public static void initSslEngine() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        __sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        __sslCtxFactory.setKeyStorePassword("storepwd");
        __sslCtxFactory.setKeyManagerPassword("keypwd");
        __sslCtxFactory.setTrustAll(true);
        __sslCtxFactory.start();
    }
    
    @Override
    protected Socket newClient() throws IOException
    {
        SSLSocket socket = __sslCtxFactory.newSslSocket();
        socket.connect(_connector.socket().getLocalSocketAddress());
        return socket;
    }

    @Override
    protected AsyncConnection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
    {
        try
        {
            AsyncConnection delegate = super.newConnection(channel,endpoint);
            SSLEngine engine = __sslCtxFactory.newSslEngine();
            engine.setUseClientMode(false);
            engine.beginHandshake();
            return new SslConnection(engine,delegate,endpoint);
        }
        catch(SSLException e)
        {
            throw new RuntimeException(e);
        }
        
    }
    

}
