package org.eclipse.jetty.spdy;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.nio.SPDYClient;
import org.eclipse.jetty.spdy.nio.SPDYServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.Assume;
import org.junit.Before;

public class SSLSPDYSynReplyTest extends SPDYSynReplyTest
{
    @Override
    protected Connector newSPDYServerConnector(ServerSessionFrameListener listener)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYServerConnector(listener, sslContextFactory);
    }

    @Override
    protected SPDYClient.Factory newSPDYClientFactory(ThreadPool threadPool)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYClient.Factory(threadPool, sslContextFactory);
    }

    @Before
    public void init()
    {
        try
        {
            getClass().getClassLoader().loadClass("org.eclipse.jetty.npn.Agent");
        }
        catch (ClassNotFoundException x)
        {
            Assume.assumeNoException(x);
        }
        NextProtoNego.debug = true;
    }
}
