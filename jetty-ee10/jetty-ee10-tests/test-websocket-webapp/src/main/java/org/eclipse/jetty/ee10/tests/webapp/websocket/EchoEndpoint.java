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

package org.eclipse.jetty.ee10.tests.webapp.websocket;

import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/", decoders = {StringSequenceDecoder.class})
public class EchoEndpoint
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EchoEndpoint.class);

    @OnMessage
    public String echo(StringSequence echo)
    {
        return echo.toString();
    }

    @OnOpen
    public void onOpen(Session session)
    {
        LOGGER.info("Session opened");
    }
}
