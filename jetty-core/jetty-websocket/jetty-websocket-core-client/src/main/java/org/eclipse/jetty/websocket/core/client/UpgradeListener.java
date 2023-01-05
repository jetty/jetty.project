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

package org.eclipse.jetty.websocket.core.client;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;

public interface UpgradeListener
{
    /**
     * Event that triggers before the Handshake request is sent.
     *
     * @param request the request
     */
    default void onHandshakeRequest(Request request)
    {
    }

    /**
     * Event that triggers after the Handshake response has been received.
     *
     * @param request the request that was used
     * @param response the response that was received
     */
    default void onHandshakeResponse(Request request, Response response)
    {
    }
}
