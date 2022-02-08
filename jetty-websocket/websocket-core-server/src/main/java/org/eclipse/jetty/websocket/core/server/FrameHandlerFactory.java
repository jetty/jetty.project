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

package org.eclipse.jetty.websocket.core.server;

import org.eclipse.jetty.websocket.core.FrameHandler;

/**
 * Factory for FrameHandler instances
 */
public interface FrameHandlerFactory
{
    /**
     * Create a FrameHandler from the provided websocketPojo.
     *
     * @param websocketPojo the websocket pojo to work with
     * @param upgradeRequest the Upgrade Handshake Request used to create the FrameHandler
     * @param upgradeResponse the Upgrade Handshake Response used to create the FrameHandler
     * @return the API specific FrameHandler, or null if this implementation is unable to create
     * the FrameHandler (allowing another {@link FrameHandlerFactory} to try)
     */
    FrameHandler newFrameHandler(Object websocketPojo, ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse);
}
