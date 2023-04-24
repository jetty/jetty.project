//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.endpoints.adapters;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

/**
 * Example EchoSocket using Adapter.
 */
public class AdapterEchoSocket extends Session.Listener.Abstract
{
    @Override
    public void onWebSocketText(String message)
    {
        if (isOpen())
        {
            System.out.printf("Echoing back message [%s]%n", message);
            // echo the message back
            getSession().sendText(message, Callback.NOOP);
        }
    }
}
