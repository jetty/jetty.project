/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
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

        Settings.Setting setting1 = settings.get(Settings.ID.MAX_CONCURRENT_STREAMS);
        Assert.assertSame(Settings.ID.MAX_CONCURRENT_STREAMS, setting1.getId());
        Assert.assertSame(Settings.Flag.PERSIST, setting1.getFlag());
        Assert.assertEquals(streamsValue, setting1.getValue());

        Settings.Setting setting2 = settings.get(Settings.ID.INITIAL_WINDOW_SIZE);
        Assert.assertSame(Settings.ID.INITIAL_WINDOW_SIZE, setting2.getId());
        Assert.assertSame(Settings.Flag.NONE, setting2.getFlag());
        Assert.assertEquals(windowValue, setting2.getValue());
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
                session.settings(serverSettingsInfo);
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
}
