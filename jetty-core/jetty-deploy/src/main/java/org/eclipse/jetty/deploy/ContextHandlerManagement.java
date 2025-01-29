//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Deployment Manager specific methods that manage ContextHandler.
 */
public interface ContextHandlerManagement
{
    Server getServer();

    // TODO: document methods
    void addContextHandler(ContextHandler contextHandler, String goalName);

    void requestContextHandlerGoal(ContextHandler contextHandler, String goalName);

    void removeContextHandler(ContextHandler contextHandler, String goalName);
}