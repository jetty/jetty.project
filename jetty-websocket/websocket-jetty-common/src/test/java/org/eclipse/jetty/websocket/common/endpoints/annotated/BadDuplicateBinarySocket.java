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

import java.io.InputStream;

import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Invalid Socket: Annotate 2 methods with interest in Binary Messages.
 */
@WebSocket
public class BadDuplicateBinarySocket
{
    /**
     * First method
     *
     * @param payload the payload
     * @param offset the offset
     * @param len the len
     */
    @OnWebSocketMessage
    public void binMe(byte[] payload, int offset, int len)
    {
        /* ignore */
    }

    /**
     * Second method (also binary)
     *
     * @param stream the input stream
     */
    @OnWebSocketMessage
    public void streamMe(InputStream stream)
    {
        /* ignore */
    }
}
