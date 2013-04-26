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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.*;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.jsr356.decoders.CharacterDecoder;
import org.eclipse.jetty.websocket.jsr356.samples.DualDecoder;
import org.eclipse.jetty.websocket.jsr356.samples.Fruit;
import org.eclipse.jetty.websocket.jsr356.samples.FruitDecoder;
import org.junit.Assert;
import org.junit.Test;

public class DecodersTest
{
    private DecoderMetadataFactory factory = new DecoderMetadataFactory();

    @Test
    public void testGetTextDecoder_Character() throws DeploymentException
    {
        SimpleClientEndpointConfig config = new SimpleClientEndpointConfig();
        config.addDecoder(FruitDecoder.class);
        Decoders decoders = new Decoders(factory,config);

        Decoder txtDecoder = decoders.getDecoder(Character.class);
        Assert.assertThat("Text Decoder",txtDecoder,notNullValue());
        Assert.assertThat("Text Decoder",txtDecoder,instanceOf(CharacterDecoder.class));
    }

    @Test
    public void testGetTextDecoder_Dual()
    {
        try
        {
            SimpleClientEndpointConfig config = new SimpleClientEndpointConfig();
            config.addDecoder(DualDecoder.class); // has duplicated support for the same target Type
            @SuppressWarnings("unused")
            Decoders decoders = new Decoders(factory,config);
            Assert.fail("Should have thrown DeploymentException");
        }
        catch (DeploymentException e)
        {
            Assert.assertThat("Error Message",e.getMessage(),containsString("duplicate"));
        }
    }

    @Test
    public void testGetTextDecoder_Fruit() throws DeploymentException
    {
        SimpleClientEndpointConfig config = new SimpleClientEndpointConfig();
        config.addDecoder(FruitDecoder.class);
        Decoders decoders = new Decoders(factory,config);

        Decoder txtDecoder = decoders.getDecoder(Fruit.class);
        Assert.assertThat("Text Decoder",txtDecoder,notNullValue());
        Assert.assertThat("Text Decoder",txtDecoder,instanceOf(FruitDecoder.class));
    }
}
