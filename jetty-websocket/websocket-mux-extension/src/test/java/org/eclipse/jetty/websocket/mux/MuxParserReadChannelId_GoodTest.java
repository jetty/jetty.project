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

import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests of valid ChannelID parsing
 */
@RunWith(Parameterized.class)
public class MuxParserReadChannelId_GoodTest
{
    private static MuxParser parser = new MuxParser();

    @Parameters
    public static Collection<Object[]> data()
    {
        // Various good Channel IDs
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        // - 1 byte tests
        data.add(new Object[]{"00", 0L});
        data.add(new Object[]{"01", 1L});
        data.add(new Object[]{"02", 2L});
        data.add(new Object[]{"7F", 127L});
        // extra bytes (not related to channelId)
        data.add(new Object[]{"37FF", 55L});
        data.add(new Object[]{"0123456789", 0x01L});

        // - 2 byte tests
        data.add(new Object[]{"8080", 0x00_80L});
        data.add(new Object[]{"80FF", 0x00_FFL});
        data.add(new Object[]{"BFFF", 0x3F_FFL});
        // extra bytes (not related to channelId)
        data.add(new Object[]{"8123456789", 0x01_23L});

        // - 3 byte tests
        data.add(new Object[]{"C0FFFF", 0x00_FF_FFL});
        data.add(new Object[]{"DFFFFF", 0x1F_FF_FFL});
        // extra bytes (not related to channelId)
        data.add(new Object[]{"C123456789", 0x01_23_45L});

        // - 3 byte tests
        data.add(new Object[]{"E0FFFFFF", 0x00_FF_FF_FFL});
        data.add(new Object[]{"FFFFFFFF", 0x1F_FF_FF_FFL});
        // extra bytes (not related to channelId)
        data.add(new Object[]{"E123456789", 0x01_23_45_67L});

        // @formatter:on
        return data;
    }

    @Rule
    public TestName testname = new TestName();

    private String rawhex;
    private byte buf[];
    private long expected;

    public MuxParserReadChannelId_GoodTest(String rawhex, long expected)
    {
        this.rawhex = rawhex;
        this.buf = TypeUtil.fromHexString(rawhex);
        this.expected = expected;
    }

    @Test
    public void testReadChannelId()
    {
        System.err.printf("Running %s.%s - hex: %s%n",this.getClass().getName(),testname.getMethodName(),rawhex);
        ByteBuffer bbuf = ByteBuffer.wrap(buf);
        long actual = parser.readChannelId(bbuf);
        Assert.assertThat("Channel ID from buffer [" + rawhex + "]",actual,is(expected));
    }
}
