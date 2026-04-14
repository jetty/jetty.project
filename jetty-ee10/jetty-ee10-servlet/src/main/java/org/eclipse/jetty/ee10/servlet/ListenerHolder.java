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

package org.eclipse.jetty.ee10.servlet;

import java.util.EventListener;

import org.eclipse.jetty.ee.servlet.Source;

/**
 * Specialization of BaseHolder for servlet listeners. This
 * allows us to record where the listener originated - web.xml,
 * annotation, api etc.
 */
public class ListenerHolder extends org.eclipse.jetty.ee.servlet.ListenerHolder
{
    public ListenerHolder()
    {
        super();
    }

    public ListenerHolder(Source source)
    {
        super(source);
    }

    public ListenerHolder(Class<? extends EventListener> listenerClass)
    {
        super(listenerClass);
    }
}
