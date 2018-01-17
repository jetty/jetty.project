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
    void onHandshakeRequest(HttpRequest request);

    /**
     * Event that triggers after the Handshake response has been received.
     *
     * @param request the request that was used
     * @param response the response that was received
     */
    void onHandshakeResponse(HttpRequest request, HttpResponse response);
}
