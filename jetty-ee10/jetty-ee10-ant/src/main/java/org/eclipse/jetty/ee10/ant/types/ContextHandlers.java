//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ee10.ant.types;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Specifies <code>&lt;contextHandlers/&gt;</code> element in web app configuration.
 */
public class ContextHandlers
{
    private List<ContextHandler> contextHandlers = new ArrayList<ContextHandler>();

    public void add(ContextHandler handler)
    {
        contextHandlers.add(handler);
    }

    public List<ContextHandler> getContextHandlers()
    {
        return contextHandlers;
    }
}
