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

import org.eclipse.jetty.websocket.jsr356.decoders.samples.DualDecoder;
import org.eclipse.jetty.websocket.jsr356.decoders.samples.SecondDecoder;
import org.junit.Assert;
import org.junit.Test;

public class DecodersTest
{
    @Test
    public void testGetDecoders_Dual() throws DeploymentException
    {
        Decoders decoders = new Decoders();
        decoders.add(DualDecoder.class);

        Decoder txtDecoder = decoders.getTextDecoder(Integer.class);
        Assert.assertThat("Text Decoder",txtDecoder,notNullValue());
        Assert.assertThat("Text Decoder",txtDecoder,instanceOf(DualDecoder.class));
    }

    @Test
    public void testGetDecoders_Second() throws DeploymentException
    {
        Decoders decoders = new Decoders();
        decoders.add(SecondDecoder.class);

        Decoder txtDecoder = decoders.getTextDecoder(Integer.class);
        Assert.assertThat("Text Decoder",txtDecoder,notNullValue());
        Assert.assertThat("Text Decoder",txtDecoder,instanceOf(SecondDecoder.class));
    }
}
