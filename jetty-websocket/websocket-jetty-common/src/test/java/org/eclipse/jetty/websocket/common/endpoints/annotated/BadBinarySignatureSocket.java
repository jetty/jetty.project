//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.endpoints.annotated;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Invalid Socket: Annotate a message interest on a method with a return type.
 */
@WebSocket
public class BadBinarySignatureSocket
{
    /**
     * Declaring a non-void return type
     *
     * @param session the session
     * @param buf the buffer
     * @param offset the offset
     * @param len the length
     * @return the response boolean
     */
    @OnWebSocketMessage
    public boolean onBinary(Session session, byte buf[], int offset, int len)
    {
        return false;
    }
}
