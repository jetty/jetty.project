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

package org.eclipse.jetty.websocket.api;

/**
 * Interface for Listeners that are interested in knowing about the Session history.
 */
public interface WebSocketSessionListener
{
    default void onWebSocketSessionCreated(Session session)
    {
    }

    default void onWebSocketSessionOpened(Session session)
    {
    }

    default void onWebSocketSessionClosed(Session session)
    {
    }
}
