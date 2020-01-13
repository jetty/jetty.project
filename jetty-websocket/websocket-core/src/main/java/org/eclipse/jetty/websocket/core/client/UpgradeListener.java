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

package org.eclipse.jetty.websocket.core.client;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;

public interface UpgradeListener
{
    /**
     * Event that triggers before the Handshake request is sent.
     *
     * @param request the request
     */
    default void onHandshakeRequest(HttpRequest request)
    {
    }

    /**
     * Event that triggers after the Handshake response has been received.
     *
     * @param request the request that was used
     * @param response the response that was received
     */
    default void onHandshakeResponse(HttpRequest request, HttpResponse response)
    {
    }
}
