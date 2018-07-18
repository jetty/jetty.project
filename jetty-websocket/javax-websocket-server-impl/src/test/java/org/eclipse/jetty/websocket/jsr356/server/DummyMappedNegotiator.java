//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.websocket.servlet.MappedWebSocketServletNegotiator;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletNegotiator;

public class DummyMappedNegotiator implements MappedWebSocketServletNegotiator
{
    @Override
    public PathSpec parsePathSpec(String rawSpec)
    {
        return null;
    }

    @Override
    public void addMapping(PathSpec pathSpec, WebSocketServletNegotiator negotiator)
    {

    }

    @Override
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
    {

    }

    @Override
    public WebSocketServletNegotiator getMapping(PathSpec pathSpec)
    {
        return null;
    }

    @Override
    public boolean removeMapping(PathSpec pathSpec)
    {
        return false;
    }

    @Override
    public MappedResource<WebSocketServletNegotiator> getMatch(String target)
    {
        return null;
    }
}
