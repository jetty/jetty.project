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
        System.gc();
        Thread.sleep(1000);

        Field field = NextProtoNego.class.getDeclaredField("objects");
        field.setAccessible(true);
        Map<Object, NextProtoNego.Provider> objects = (Map<Object, NextProtoNego.Provider>)field.get(null);
        int initialSize = objects.size();

        avoidStackLocalVariables();
        // Allow the close to arrive to the server and the selector to process it
        Thread.sleep(1000);

        // Perform GC to be sure that the WeakHashMap is cleared
        System.gc();
        Thread.sleep(1000);

        // Check that the WeakHashMap is empty
        Assert.assertEquals(initialSize, objects.size());
    }

    private void avoidStackLocalVariables() throws Exception
    {
        Session session = startClient(startServer(null), null);
        session.goAway().get(5, TimeUnit.SECONDS);
    }
}
