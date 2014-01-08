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

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class SettingsGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        byte flags = SettingsInfo.CLEAR_PERSISTED;
        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.MAX_CONCURRENT_STREAMS, Settings.Flag.PERSIST, 100));
        settings.put(new Settings.Setting(Settings.ID.ROUND_TRIP_TIME, Settings.Flag.PERSISTED, 500));
        SettingsFrame frame1 = new SettingsFrame(SPDY.V2, flags, settings);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.SETTINGS, frame2.getType());
        SettingsFrame settingsFrame = (SettingsFrame)frame2;
        Assert.assertEquals(SPDY.V2, settingsFrame.getVersion());
        Assert.assertEquals(flags, settingsFrame.getFlags());
        Assert.assertEquals(settings, settingsFrame.getSettings());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        byte flags = SettingsInfo.CLEAR_PERSISTED;
        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.DOWNLOAD_RETRANSMISSION_RATE, 100));
        settings.put(new Settings.Setting(Settings.ID.ROUND_TRIP_TIME, 500));
        SettingsFrame frame1 = new SettingsFrame(SPDY.V2, flags, settings);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.SETTINGS, frame2.getType());
        SettingsFrame settingsFrame = (SettingsFrame)frame2;
        Assert.assertEquals(SPDY.V2, settingsFrame.getVersion());
        Assert.assertEquals(flags, settingsFrame.getFlags());
        Assert.assertEquals(settings, settingsFrame.getSettings());
    }
}
