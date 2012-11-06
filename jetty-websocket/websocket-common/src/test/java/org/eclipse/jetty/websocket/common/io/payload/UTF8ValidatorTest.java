//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.common.io.payload.UTF8Validator;
import org.junit.Assert;
import org.junit.Test;

public class UTF8ValidatorTest
{
    private ByteBuffer asByteBuffer(String hexStr)
    {
        byte buf[] = TypeUtil.fromHexString(hexStr);
        return ByteBuffer.wrap(buf);
    }

    @Test
    public void testCase6_4_3()
    {
        ByteBuffer part1 = asByteBuffer("cebae1bdb9cf83cebcceb5"); // good
        ByteBuffer part2 = asByteBuffer("f4908080"); // INVALID
        ByteBuffer part3 = asByteBuffer("656469746564"); // good

        UTF8Validator validator = new UTF8Validator();
        validator.process(part1); // good
        try
        {
            validator.process(part2); // bad
            Assert.fail("Expected a " + BadPayloadException.class);
        }
        catch (BadPayloadException e)
        {
            // expected path
        }
        validator.process(part3); // good
    }
}
