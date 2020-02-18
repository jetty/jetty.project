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

package org.eclipse.jetty.server;

import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * A Jetty Server Handler.
 * <p>
 * A Handler instance is required by a {@link Server} to handle incoming
 * HTTP requests.
 * <p>
 * A Handler may:
 * <ul>
 * <li>Completely generate the HTTP Response</li>
 * <li>Examine/modify the request and call another Handler (see {@link HandlerWrapper}).
 * <li>Pass the request to one or more other Handlers (see {@link HandlerCollection}).
 * </ul>
 *
 * Handlers are passed the servlet API request and response object, but are
 * not Servlets.  The servlet container is implemented by handlers for
 * context, security, session and servlet that modify the request object
 * before passing it to the next stage of handling.
 */
@ManagedObject("Jetty Handler")
public interface Handler extends Handle, LifeCycle, Destroyable
{
    public void setServer(Server server);

    @ManagedAttribute(value = "the jetty server for this handler", readonly = true)
    public Server getServer();

    @ManagedOperation(value = "destroy associated resources", impact = "ACTION")
    @Override
    public void destroy();
}

