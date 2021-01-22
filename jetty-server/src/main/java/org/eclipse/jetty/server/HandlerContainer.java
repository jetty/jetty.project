//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * A Handler that contains other Handlers.
 * <p>
 * The contained handlers may be one (see @{link {@link org.eclipse.jetty.server.handler.HandlerWrapper})
 * or many (see {@link org.eclipse.jetty.server.handler.HandlerList} or {@link org.eclipse.jetty.server.handler.HandlerCollection}.
 */
@ManagedObject("Handler of Multiple Handlers")
public interface HandlerContainer extends LifeCycle
{

    /**
     * @return array of handlers directly contained by this handler.
     */
    @ManagedAttribute("handlers in this container")
    public Handler[] getHandlers();

    /**
     * @return array of all handlers contained by this handler and it's children
     */
    @ManagedAttribute("all contained handlers")
    public Handler[] getChildHandlers();

    /**
     * @param byclass the child handler class to get
     * @return array of all handlers contained by this handler and it's children of the passed type.
     */
    public Handler[] getChildHandlersByClass(Class<?> byclass);

    /**
     * @param byclass the child handler class to get
     * @param <T> the type of handler
     * @return first handler of all handlers contained by this handler and it's children of the passed type.
     */
    public <T extends Handler> T getChildHandlerByClass(Class<T> byclass);
}
