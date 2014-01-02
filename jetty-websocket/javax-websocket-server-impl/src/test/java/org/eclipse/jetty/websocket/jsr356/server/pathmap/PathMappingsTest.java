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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

import static org.hamcrest.Matchers.notNullValue;

import org.eclipse.jetty.websocket.server.pathmap.PathMappings;
import org.eclipse.jetty.websocket.server.pathmap.PathMappings.MappedResource;
import org.eclipse.jetty.websocket.server.pathmap.ServletPathSpec;
import org.junit.Assert;
import org.junit.Test;

public class PathMappingsTest
{
    private void assertMatch(PathMappings<String> pathmap, String path, String expectedValue)
    {
        String msg = String.format(".getMatch(\"%s\")",path);
        MappedResource<String> match = pathmap.getMatch(path);
        Assert.assertThat(msg,match,notNullValue());
        String actualMatch = match.getResource();
        Assert.assertEquals(msg,expectedValue,actualMatch);
    }

    public void dumpMappings(PathMappings<String> p)
    {
        for (MappedResource<String> res : p)
        {
            System.out.printf("  %s%n",res);
        }
    }

    /**
     * Test the match order rules with a mixed Servlet and WebSocket path specs
     * <p>
     * <ul>
     * <li>Exact match</li>
     * <li>Longest prefix match</li>
     * <li>Longest suffix match</li>
     * </ul>
     */
    @Test
    public void testMixedMatchOrder()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/"),"default");
        p.put(new ServletPathSpec("/animal/bird/*"),"birds");
        p.put(new ServletPathSpec("/animal/fish/*"),"fishes");
        p.put(new ServletPathSpec("/animal/*"),"animals");
        p.put(new WebSocketPathSpec("/animal/{type}/{name}/chat"),"animalChat");
        p.put(new WebSocketPathSpec("/animal/{type}/{name}/cam"),"animalCam");
        p.put(new WebSocketPathSpec("/entrance/cam"),"entranceCam");

        // dumpMappings(p);

        assertMatch(p,"/animal/bird/eagle","birds");
        assertMatch(p,"/animal/fish/bass/sea","fishes");
        assertMatch(p,"/animal/peccary/javalina/evolution","animals");
        assertMatch(p,"/","default");
        assertMatch(p,"/animal/bird/eagle/chat","animalChat");
        assertMatch(p,"/animal/bird/penguin/chat","animalChat");
        assertMatch(p,"/animal/fish/trout/cam","animalCam");
        assertMatch(p,"/entrance/cam","entranceCam");
    }

    /**
     * Test the match order rules imposed by the WebSocket API (JSR-356)
     * <p>
     * <ul>
     * <li>Exact match</li>
     * <li>Longest prefix match</li>
     * <li>Longest suffix match</li>
     * </ul>
     */
    @Test
    public void testWebsocketMatchOrder()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new WebSocketPathSpec("/a/{var}/c"),"endpointA");
        p.put(new WebSocketPathSpec("/a/b/c"),"endpointB");
        p.put(new WebSocketPathSpec("/a/{var1}/{var2}"),"endpointC");
        p.put(new WebSocketPathSpec("/{var1}/d"),"endpointD");
        p.put(new WebSocketPathSpec("/b/{var2}"),"endpointE");

        // dumpMappings(p);

        assertMatch(p,"/a/b/c","endpointB");
        assertMatch(p,"/a/d/c","endpointA");
        assertMatch(p,"/a/x/y","endpointC");

        assertMatch(p,"/b/d","endpointE");
    }
}
