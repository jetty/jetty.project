//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class HandshakerSelector implements Handshaker
{
    private List<Handshaker> handshakers = new ArrayList<>();

    public HandshakerSelector(Handshaker... handshakers)
    {
        Collections.addAll(this.handshakers, handshakers);
    }

    @Override
    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response, FrameHandler.Customizer defaultCustomizer) throws IOException
    {
        // TODO: we don't want to do a lot of work for every request that is not websocket.
        //  Something like: if (method == CONNECT) only try 8441, else if (method == GET) only try 6455.
        // TODO: optimise (do pre checks and avoid iterating through handshakers)
        // TODO: minimum simplest thing to do to return false
        for (Handshaker handshaker : handshakers)
        {
            if (handshaker.upgradeRequest(negotiator, request, response, defaultCustomizer))
                return true;

            if (response.isCommitted())
                return false;
        }
        return false;
    }
}
