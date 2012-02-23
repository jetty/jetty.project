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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SettingsTest extends AbstractTest
{
    @Test
    public void testSettings() throws Exception
    {
        Map<SettingsInfo.Key,Integer> settings = new HashMap<>();
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.UPLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.DOWNLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSISTED | SettingsInfo.Key.CURRENT_CONGESTION_WINDOW), 1024);
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

        session.settings(clientSettingsInfo);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSettings() throws Exception
    {
        Map<SettingsInfo.Key,Integer> settings = new HashMap<>();
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.UPLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.DOWNLOAD_BANDWIDTH), 1024 * 1024);
        settings.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSIST | SettingsInfo.Key.CURRENT_CONGESTION_WINDOW), 1024);
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
                Assert.assertEquals(serverSettingsInfo, clientSettingsInfo);
                latch.countDown();
            }
        };

        startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
