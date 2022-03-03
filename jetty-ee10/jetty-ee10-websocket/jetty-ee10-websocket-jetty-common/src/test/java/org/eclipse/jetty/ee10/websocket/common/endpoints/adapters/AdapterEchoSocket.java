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

package org.eclipse.jetty.ee10.websocket.common.endpoints.adapters;

import java.io.IOException;

import org.eclipse.jetty.ee10.websocket.api.WebSocketAdapter;

/**
 * Example EchoSocket using Adapter.
 */
public class AdapterEchoSocket extends WebSocketAdapter
{
    @Override
    public void onWebSocketText(String message)
    {
        if (isConnected())
        {
            try
            {
                System.out.printf("Echoing back message [%s]%n", message);
                // echo the message back
                getRemote().sendString(message);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
    }
}
