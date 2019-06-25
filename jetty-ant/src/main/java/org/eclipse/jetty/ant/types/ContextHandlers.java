//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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

package org.eclipse.jetty.ant.types;

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
