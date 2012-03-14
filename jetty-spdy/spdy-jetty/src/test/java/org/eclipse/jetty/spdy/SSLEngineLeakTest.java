package org.eclipse.jetty.spdy;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class SSLEngineLeakTest extends AbstractTest
{
    @Override
    protected SPDYServerConnector newSPDYServerConnector(ServerSessionFrameListener listener)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYServerConnector(listener, sslContextFactory);
    }

    @Override
    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYClient.Factory(threadPool, sslContextFactory);
    }

    @Test
    public void testSSLEngineLeak() throws Exception
    {
        avoidStackLocalVariables();
        Thread.sleep(1000);
        System.gc();
        Field field = NextProtoNego.class.getDeclaredField("objects");
        field.setAccessible(true);
        Map<Object, NextProtoNego.Provider> objects = (Map<Object, NextProtoNego.Provider>)field.get(null);
        Assert.assertEquals(0, objects.size());
    }

    private void avoidStackLocalVariables() throws Exception
    {
        Session session = startClient(startServer(null), null);
        session.goAway().get(5, TimeUnit.SECONDS);
    }
}
