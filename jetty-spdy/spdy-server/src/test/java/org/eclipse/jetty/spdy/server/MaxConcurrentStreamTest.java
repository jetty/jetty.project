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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MaxConcurrentStreamTest extends AbstractTest
{
    @Test
    public void testMaxConcurrentStreamsSetByServer() throws Exception, ExecutionException
    {
        final CountDownLatch settingsReceivedLatch = new CountDownLatch(1);
        final CountDownLatch dataReceivedLatch = new CountDownLatch(1);

        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                Settings settings = new Settings();
                settings.put(new Settings.Setting(Settings.ID.MAX_CONCURRENT_STREAMS, 1));
                try
                {
                    session.settings(new SettingsInfo(settings));
                }
                catch (ExecutionException | InterruptedException | TimeoutException e)
                {
                    e.printStackTrace();
                }
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    stream.reply(new ReplyInfo(true));
                }
                catch (ExecutionException | InterruptedException | TimeoutException e)
                {
                    e.printStackTrace();
                }
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataReceivedLatch.countDown();
                    }
                };
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsReceivedLatch.countDown();
            }
        });

        assertThat("Settings frame received", settingsReceivedLatch.await(5, TimeUnit.SECONDS), is(true));

        SynInfo synInfo = new SynInfo(new Fields(), false);
        Stream stream = session.syn(synInfo, null);

        boolean failed = false;
        try
        {
            session.syn(synInfo, null);
        }
        catch (ExecutionException | InterruptedException | TimeoutException e)
        {
            failed = true;
        }

        assertThat("Opening second stream failed", failed, is(true));

        stream.data(new ByteBufferDataInfo(BufferUtil.EMPTY_BUFFER, true));
        assertThat("Data has been received on first stream.", dataReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
    }
}
