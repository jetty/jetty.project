package org.eclipse.jetty.spdy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SPDYSettingsTest extends SPDYTest
{
    @Test
    public void testSettings() throws Exception
    {
        Map<SettingsInfo.Key,Integer> settings = new HashMap<>();
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.UPLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.DOWNLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSISTED | SettingsInfo.Key.CONGESTION_WINDOW), 1024);
        final SettingsInfo clientSettingsInfo = new SettingsInfo(settings);
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo serverSettingsInfo)
            {
                Assert.assertEquals(clientSettingsInfo, serverSettingsInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), null);

        session.settings((short)2, clientSettingsInfo);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSettings() throws Exception
    {
        Map<SettingsInfo.Key,Integer> settings = new HashMap<>();
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.UPLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.DOWNLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSIST | SettingsInfo.Key.CONGESTION_WINDOW), 1024);
        final SettingsInfo serverSettingsInfo = new SettingsInfo(settings);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                session.settings((short)2, serverSettingsInfo);
            }
        };

        final CountDownLatch latch = new CountDownLatch(1);
        Session.FrameListener clientSessionFrameListener = new Session.FrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo clientSettingsInfo)
            {
                Assert.assertEquals(serverSettingsInfo, clientSettingsInfo);
                latch.countDown();
            }
        };

        startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
