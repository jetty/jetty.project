//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.server;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.Assert;
import org.junit.Test;

public class SettingsTest extends AbstractTest
{
    @Test
    public void testSettingsUsage() throws Exception
    {
        Settings settings = new Settings();
        int streamsValue = 100;
        settings.put(new Settings.Setting(Settings.ID.MAX_CONCURRENT_STREAMS, Settings.Flag.PERSIST, streamsValue));
        int windowValue = 32768;
        settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowValue));
        int newCode = 91;
        Settings.ID newID = Settings.ID.from(newCode);
        int newValue = 97;
        settings.put(new Settings.Setting(newID, newValue));

        Settings.Setting setting1 = settings.get(Settings.ID.MAX_CONCURRENT_STREAMS);
        Assert.assertSame(Settings.ID.MAX_CONCURRENT_STREAMS, setting1.id());
        Assert.assertSame(Settings.Flag.PERSIST, setting1.flag());
        Assert.assertEquals(streamsValue, setting1.value());

        Settings.Setting setting2 = settings.get(Settings.ID.INITIAL_WINDOW_SIZE);
        Assert.assertSame(Settings.ID.INITIAL_WINDOW_SIZE, setting2.id());
        Assert.assertSame(Settings.Flag.NONE, setting2.flag());
        Assert.assertEquals(windowValue, setting2.value());

        int size = settings.size();
        Settings.Setting setting3 = settings.remove(Settings.ID.from(newCode));
        Assert.assertEquals(size - 1, settings.size());
        Assert.assertNotNull(setting3);
        Assert.assertSame(newID, setting3.id());
        Assert.assertEquals(newValue, setting3.value());
    }

    @Test
    public void testSettings() throws Exception
    {
        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.UPLOAD_BANDWIDTH, 1024 * 1024));
        settings.put(new Settings.Setting(Settings.ID.DOWNLOAD_BANDWIDTH, 1024 * 1024));
        settings.put(new Settings.Setting(Settings.ID.CURRENT_CONGESTION_WINDOW, Settings.Flag.PERSISTED, 1024));
        final SettingsInfo clientSettingsInfo = new SettingsInfo(settings);
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo serverSettingsInfo)
            {
                Assert.assertEquals(clientSettingsInfo.getFlags(), serverSettingsInfo.getFlags());
                Assert.assertEquals(clientSettingsInfo.getSettings(), serverSettingsInfo.getSettings());
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), null);

        session.settings(clientSettingsInfo);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSettings() throws Exception
    {
        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.UPLOAD_BANDWIDTH, 1024 * 1024));
        settings.put(new Settings.Setting(Settings.ID.DOWNLOAD_BANDWIDTH, 1024 * 1024));
        settings.put(new Settings.Setting(Settings.ID.CURRENT_CONGESTION_WINDOW, Settings.Flag.PERSIST, 1024));
        final SettingsInfo serverSettingsInfo = new SettingsInfo(settings);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                session.settings(serverSettingsInfo, new FutureCallback());
            }
        };

        final CountDownLatch latch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo clientSettingsInfo)
            {
                Assert.assertEquals(serverSettingsInfo.getFlags(), clientSettingsInfo.getFlags());
                Assert.assertEquals(serverSettingsInfo.getSettings(), clientSettingsInfo.getSettings());
                latch.countDown();
            }
        };

        startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSettingIDIsTheSameInBothV2AndV3() throws Exception
    {
        final AtomicReference<SettingsInfo> v2 = new AtomicReference<>();
        final AtomicReference<SettingsInfo> v3 = new AtomicReference<>();
        final CountDownLatch settingsLatch = new CountDownLatch(2);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                int count = this.count.incrementAndGet();
                if (count == 1)
                    v2.set(settingsInfo);
                else if (count == 2)
                    v3.set(settingsInfo);
                else
                    Assert.fail();
                settingsLatch.countDown();
            }
        });

        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, Settings.Flag.PERSIST, 0xC0_00));
        SettingsInfo settingsInfo = new SettingsInfo(settings);

        Session sessionV2 = startClient(address, null);
        sessionV2.settings(settingsInfo);

        Session sessionV3 = clientFactory.newSPDYClient(SPDY.V3).connect(address, null);
        sessionV3.settings(settingsInfo);

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(v2.get().getSettings(), v3.get().getSettings());
    }
}
