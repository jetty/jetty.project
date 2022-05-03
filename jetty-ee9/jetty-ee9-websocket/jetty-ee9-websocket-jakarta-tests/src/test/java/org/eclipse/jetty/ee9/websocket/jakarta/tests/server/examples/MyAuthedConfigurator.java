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

package org.eclipse.jetty.websocket.jakarta.tests.server.examples;

import java.security.Principal;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

public class MyAuthedConfigurator extends ServerEndpointConfig.Configurator
{
    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
    {
        // Is Authenticated?
        Principal principal = request.getUserPrincipal();
        if (principal == null)
        {
            throw new RuntimeException("Not authenticated");
        }

        // Is Authorized?
        if (!request.isUserInRole("websocket"))
        {
            throw new RuntimeException("Not authorized");
        }

        // normal operation
        super.modifyHandshake(sec, request, response);
    }
}
