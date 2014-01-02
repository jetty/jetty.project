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

import static org.hamcrest.Matchers.containsString;

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
 * Tests of Invalid ChannelID parsing
 */
@RunWith(Parameterized.class)
public class MuxParserReadChannelId_BadEncodingTest
{
    private static MuxParser parser = new MuxParser();

    @Parameters
    public static Collection<Object[]> data()
    {
        // Various Invalid Encoded Channel IDs.
        // Violating "minimal number of bytes necessary" rule.
        List<Object[]> data = new ArrayList<>();

        // @formatter:off
        // - 1 byte tests
        // all known 1 byte tests are valid

        // - 2 byte tests
        data.add(new Object[]{"8000"});
        data.add(new Object[]{"8001"});
        data.add(new Object[]{"807F"});
        // extra bytes (not related to channelId)
        data.add(new Object[]{"8023456789"});

        // - 3 byte tests
        data.add(new Object[]{"C00000"});
        data.add(new Object[]{"C01234"});
        data.add(new Object[]{"C03FFF"});

        // - 3 byte tests
        data.add(new Object[]{"E0000000"});
        data.add(new Object[]{"E0000001"});
        data.add(new Object[]{"E01FFFFF"});

        // @formatter:on
        return data;
    }

    @Rule
    public TestName testname = new TestName();

    private String rawhex;
    private byte buf[];

    public MuxParserReadChannelId_BadEncodingTest(String rawhex)
    {
        this.rawhex = rawhex;
        this.buf = TypeUtil.fromHexString(rawhex);
    }

    @Test
    public void testBadEncoding()
    {
        System.err.printf("Running %s.%s - hex: %s%n",this.getClass().getName(),testname.getMethodName(),rawhex);
        ByteBuffer bbuf = ByteBuffer.wrap(buf);
        try
        {
            parser.readChannelId(bbuf);
            // unexpected path
            Assert.fail("Should have failed with an invalid parse");
        }
        catch (MuxException e)
        {
            // expected path
            Assert.assertThat(e.getMessage(),containsString("Invalid Channel ID"));
        }
    }
}
