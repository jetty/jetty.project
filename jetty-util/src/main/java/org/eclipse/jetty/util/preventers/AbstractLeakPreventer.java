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

package org.eclipse.jetty.util.preventers;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractLeakPreventer
 *
 * Abstract base class for code that seeks to avoid pinning of webapp classloaders by using the jetty classloader to
 * proactively call the code that pins them (generally pinned as static data members, or as static
 * data members that are daemon threads (which use the context classloader)).
 *
 * Instances of subclasses of this class should be set with Server.addBean(), which will
 * ensure that they are called when the Server instance starts up, which will have the jetty
 * classloader in scope.
 */
public abstract class AbstractLeakPreventer extends AbstractLifeCycle
{
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractLeakPreventer.class);

    public abstract void prevent(ClassLoader loader);

    @Override
    protected void doStart() throws Exception
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            prevent(getClass().getClassLoader());
            super.doStart();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(loader);
        }
    }
}
