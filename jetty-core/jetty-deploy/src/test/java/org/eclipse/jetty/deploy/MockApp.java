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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.Environment;

public class MockApp implements App
{
    private final String name;
    private ContextHandler contextHandler;

    public MockApp(String name)
    {
        this.name = name;
    }

    @Override
    public String getName()
    {
        return name;
    }

    public void setContextHandler(ContextHandler contextHandler)
    {
        this.contextHandler = contextHandler;
    }

    @Override
    public ContextHandler getContextHandler()
    {
        return contextHandler;
    }

    @Override
    public Environment getEnvironment()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]", this.getClass().getSimpleName(), hashCode(), getName());
    }
}
