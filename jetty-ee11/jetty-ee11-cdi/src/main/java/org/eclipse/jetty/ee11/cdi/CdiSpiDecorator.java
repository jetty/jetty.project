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

package org.eclipse.jetty.ee11.cdi;

import org.eclipse.jetty.ee.servlet.ServletContextHandler;

/**
 * A Decorator that invokes the CDI provider within a webapp to decorate objects created by
 * the contexts {@link org.eclipse.jetty.util.DecoratedObjectFactory}
 * (typically Listeners, Filters and Servlets).
 * The CDI provider is invoked using reflection to avoid any CDI instance
 * or dependencies within the server scope. The code invoked is equivalent to:
 * <pre>
 * public &lt;T&gt; T decorate(T o)
 * {
 *   BeanManager manager = CDI.current().getBeanManager();
 *   manager.createInjectionTarget(manager.createAnnotatedType((Class&lt;T&gt;)o.getClass()))
 *     .inject(o,manager.createCreationalContext(null));
 *   return o;
 * }
 * </pre>
 */
public class CdiSpiDecorator extends org.eclipse.jetty.ee.cdi.CdiSpiDecorator
{
    public CdiSpiDecorator(ServletContextHandler context) throws UnsupportedOperationException
    {
        super(context);
    }
}
