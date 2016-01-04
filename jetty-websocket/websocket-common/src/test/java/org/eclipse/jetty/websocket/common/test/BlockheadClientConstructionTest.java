//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Gotta test some basic constructors of the BlockheadClient.
 */
@RunWith(value = Parameterized.class)
public class BlockheadClientConstructionTest
{
    @Parameters
    public static Collection<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        // @formatter:off
        data.add(new Object[] { "ws://localhost/",      "http://localhost/" });
        data.add(new Object[] { "ws://localhost:8080/", "http://localhost:8080/" });
        data.add(new Object[] { "ws://webtide.com/",    "http://webtide.com/" });
        data.add(new Object[] { "ws://www.webtide.com/sockets/chat", "http://www.webtide.com/sockets/chat" });
        // @formatter:on
        return data;
    }

    private URI expectedWsUri;
    private URI expectedHttpUri;

    public BlockheadClientConstructionTest(String wsuri, String httpuri)
    {
        this.expectedWsUri = URI.create(wsuri);
        this.expectedHttpUri = URI.create(httpuri);
    }

    @Test
    public void testURIs() throws URISyntaxException
    {
        @SuppressWarnings("resource")
        BlockheadClient client = new BlockheadClient(expectedWsUri);
        Assert.assertThat("Websocket URI",client.getWebsocketURI(),is(expectedWsUri));
        Assert.assertThat("Websocket URI",client.getHttpURI(),is(expectedHttpUri));
    }

}
