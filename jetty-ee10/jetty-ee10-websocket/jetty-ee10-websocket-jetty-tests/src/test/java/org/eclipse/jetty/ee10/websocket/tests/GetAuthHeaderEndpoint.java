//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.tests;

import java.io.IOException;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;

@SuppressWarnings("unused")
@WebSocket
public class GetAuthHeaderEndpoint
{
    @OnWebSocketConnect
    public void onConnect(Session session) throws IOException
    {
        String authHeaderName = "Authorization";
        String authHeaderValue = session.getUpgradeRequest().getHeader(authHeaderName);
        session.getRemote().sendString("Header[" + authHeaderName + "]=" + authHeaderValue);
    }
}
