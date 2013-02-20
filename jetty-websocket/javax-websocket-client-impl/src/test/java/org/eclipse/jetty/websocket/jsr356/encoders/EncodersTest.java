//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.encoders;

import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.websocket.jsr356.ConfigurationException;
import org.eclipse.jetty.websocket.jsr356.samples.FruitBinaryEncoder;
import org.eclipse.jetty.websocket.jsr356.samples.FruitTextEncoder;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests against the Encoders class
 */
public class EncodersTest
{
    @Test
    public void testAddDuplicateEncoder()
    {
        Encoders encoders = new Encoders();
        encoders.add(FruitBinaryEncoder.class);
        try
        {
            encoders.add(FruitTextEncoder.class); // throws exception
            Assert.fail("Should have thrown ConfigurationException");
        }
        catch (ConfigurationException e)
        {
            Assert.assertThat("Error Message",e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testAddEncoder()
    {
        Encoders encoders = new Encoders();
        encoders.add(FruitBinaryEncoder.class);
    }
}
