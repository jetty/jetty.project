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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client.samples;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSEventTracker;

@ClientEndpoint
public class CloseSocket extends WSEventTracker
{
    @OnOpen
    public void onOpen(Session session)
    {
        super.onWsOpen(session);
    }

    @OnError
    public void onError(Throwable cause)
    {
        super.onWsError(cause);
    }

    @OnClose
    public void onClose()
    {
        addEvent("onClose()");
        super.onWsClose(null);
    }
}
