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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
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
        short version = 2;
        byte flags = SettingsInfo.CLEAR_PERSISTED;
        Map<SettingsInfo.Key, Integer> pairs = new HashMap<>();
        pairs.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSIST | SettingsInfo.Key.MAX_STREAMS), 100);
        pairs.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSISTED | SettingsInfo.Key.ROUND_TRIP_TIME), 500);
        SettingsFrame frame1 = new SettingsFrame(version, flags, pairs);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.SETTINGS, frame2.getType());
        SettingsFrame settings = (SettingsFrame)frame2;
        Assert.assertEquals(version, settings.getVersion());
        Assert.assertEquals(flags, settings.getFlags());
        Assert.assertEquals(pairs, settings.getSettings());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        byte flags = SettingsInfo.CLEAR_PERSISTED;
        Map<SettingsInfo.Key, Integer> pairs = new HashMap<>();
        pairs.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSIST | SettingsInfo.Key.MAX_STREAMS), 100);
        pairs.put(new SettingsInfo.Key(SettingsInfo.Key.FLAG_PERSISTED | SettingsInfo.Key.ROUND_TRIP_TIME), 500);
        SettingsFrame frame1 = new SettingsFrame(version, flags, pairs);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
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
        SettingsFrame settings = (SettingsFrame)frame2;
        Assert.assertEquals(version, settings.getVersion());
        Assert.assertEquals(flags, settings.getFlags());
        Assert.assertEquals(pairs, settings.getSettings());
    }
}
