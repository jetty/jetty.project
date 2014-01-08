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

package org.eclipse.jetty.websocket.mux;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests of valid ChannelID generation
 */
@RunWith(Parameterized.class)
public class MuxGeneratorWriteChannelIdTest
{
    private static MuxGenerator generator = new MuxGenerator();

    @Parameters
    public static Collection<Object[]> data()
    {
        // Various good Channel IDs
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        // - 1 byte tests
        data.add(new Object[]{  0L, "00"});
        data.add(new Object[]{  1L, "01"});
        data.add(new Object[]{  2L, "02"});
        data.add(new Object[]{ 55L, "37"});
        data.add(new Object[]{127L, "7F"});

        // - 2 byte tests
        data.add(new Object[]{0x00_80L, "8080"});
        data.add(new Object[]{0x00_FFL, "80FF"});
        data.add(new Object[]{0x3F_FFL, "BFFF"});

        // - 3 byte tests
        data.add(new Object[]{0x00_FF_FFL, "C0FFFF"});
        data.add(new Object[]{0x1F_FF_FFL, "DFFFFF"});

        // - 3 byte tests
        data.add(new Object[]{0x00_FF_FF_FFL, "E0FFFFFF"});
        data.add(new Object[]{0x1F_FF_FF_FFL, "FFFFFFFF"});

        // @formatter:on
        return data;
    }

    @Rule
    public TestName testname = new TestName();

    private long channelId;
    private String expectedHex;

    public MuxGeneratorWriteChannelIdTest(long channelId, String expectedHex)
    {
        this.channelId = channelId;
        this.expectedHex = expectedHex;
    }

    @Test
    public void testReadChannelId()
    {
        System.err.printf("Running %s.%s - channelId: %,d%n",this.getClass().getName(),testname.getMethodName(),channelId);
        ByteBuffer bbuf = ByteBuffer.allocate(10);
        generator.writeChannelId(bbuf,channelId);
        BufferUtil.flipToFlush(bbuf,0);
        byte actual[] = BufferUtil.toArray(bbuf);
        String actualHex = TypeUtil.toHexString(actual).toUpperCase(Locale.ENGLISH);
        Assert.assertThat("Channel ID [" + channelId + "]",actualHex,is(expectedHex));
    }
}
