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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.websocket.client.masks.ZeroMasker;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WebSocketClientFactoryTest
{
    @Rule
    public TestTracker tt = new TestTracker();

    @Test
    public void testNewSocket()
    {
        WebSocketClientFactory factory = new WebSocketClientFactory();
        factory.setMasker(new ZeroMasker());
        WebSocketClient client = factory.newWebSocketClient(new TrackingSocket());

        Assert.assertThat("Client",client,notNullValue());
        Assert.assertThat("Client.factory",client.getFactory(),is(factory));
        Assert.assertThat("Client.policy",client.getPolicy(),is(factory.getPolicy()));
        Assert.assertThat("Client.masker", client.getMasker(), notNullValue());
        Assert.assertThat("Client.upgradeRequest",client.getUpgradeRequest(),notNullValue());
        Assert.assertThat("Client.upgradeResponse",client.getUpgradeResponse(),nullValue());
    }
}
