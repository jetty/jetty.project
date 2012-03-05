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

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardByteBufferPool;
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
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory().newCompressor());
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
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory().newCompressor());
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
