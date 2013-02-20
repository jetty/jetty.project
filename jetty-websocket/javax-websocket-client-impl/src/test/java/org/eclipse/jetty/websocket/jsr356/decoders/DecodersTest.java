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

package org.eclipse.jetty.websocket.jsr356.decoders;

import static org.hamcrest.Matchers.*;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.jsr356.ConfigurationException;
import org.eclipse.jetty.websocket.jsr356.samples.DualDecoder;
import org.eclipse.jetty.websocket.jsr356.samples.Fruit;
import org.eclipse.jetty.websocket.jsr356.samples.FruitDecoder;
import org.junit.Assert;
import org.junit.Test;

public class DecodersTest
{
    @Test
    public void testGetTextDecoder_Character() throws DeploymentException
    {
        Decoders decoders = new Decoders();
        decoders.add(FruitDecoder.class);

        Decoder txtDecoder = decoders.getDecoder(Character.class);
        Assert.assertThat("Text Decoder",txtDecoder,notNullValue());
        Assert.assertThat("Text Decoder",txtDecoder,instanceOf(CharacterDecoder.class));
    }

    @Test
    public void testGetTextDecoder_Dual()
    {
        try
        {
            Decoders decoders = new Decoders();
            decoders.add(DualDecoder.class); // has duplicated support for the same target Type
            Assert.fail("Should have thrown ConfigurationException");
        }
        catch (ConfigurationException e)
        {
            Assert.assertThat("Error Message",e.getMessage(),containsString("Duplicate"));
        }
    }

    @Test
    public void testGetTextDecoder_Fruit() throws DeploymentException
    {
        Decoders decoders = new Decoders();
        decoders.add(FruitDecoder.class);

        Decoder txtDecoder = decoders.getDecoder(Fruit.class);
        Assert.assertThat("Text Decoder",txtDecoder,notNullValue());
        Assert.assertThat("Text Decoder",txtDecoder,instanceOf(FruitDecoder.class));
    }
}
