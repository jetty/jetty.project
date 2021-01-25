//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebSocketClientBadUriTest
{
    public static Stream<Arguments> data()
    {
        List<String[]> data = new ArrayList<>();

        // - not using right scheme
        data.add(new String[]{"http://localhost"});
        data.add(new String[]{"https://localhost"});
        data.add(new String[]{"file://localhost"});
        data.add(new String[]{"content://localhost"});
        data.add(new String[]{"jar://localhost"});
        // - non-absolute uri
        data.add(new String[]{"/mysocket"});
        data.add(new String[]{"/sockets/echo"});
        data.add(new String[]{"#echo"});
        data.add(new String[]{"localhost:8080/echo"});

        return data.stream().map(Arguments::of);
    }

    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testBadURI(String uriStr) throws Exception
    {
        JettyTrackingSocket wsocket = new JettyTrackingSocket();
        URI uri = URI.create(uriStr);

        assertThrows(IllegalArgumentException.class, () -> client.connect(wsocket, uri));
        wsocket.assertNotOpened();
    }
}
