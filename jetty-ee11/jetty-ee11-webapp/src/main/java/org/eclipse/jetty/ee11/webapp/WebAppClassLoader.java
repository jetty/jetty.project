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

package org.eclipse.jetty.ee11.webapp;

import java.security.PrivilegedExceptionAction;

import org.eclipse.jetty.util.ClassVisibilityChecker;

/**
 * ClassLoader for HttpContext.
 * <p>
 * Specializes URLClassLoader with some utility and file mapping
 * methods.
 * <p>
 * This loader implements the Servlet specification behavior that may invert the normal Java classloader parent priority
 * behaviour. The {@link ClassVisibilityChecker} API of the {@link WebAppClassLoader.Context} implementation is
 * used to determine which classes from the parent classloader are hidden from the context, and which are protected
 * from being overridden by the context.
 * <p>
 * Java compliant loading, where the parent loader always has priority, can be selected with the
 * {@link WebAppContext#setParentLoaderPriority(boolean)} method.
 * @deprecated use the core {@link org.eclipse.jetty.ee.webapp.WebAppClassLoader} directly instead.
 */
@Deprecated(since = "12.0.0", forRemoval = true)
public class WebAppClassLoader extends org.eclipse.jetty.ee.webapp.WebAppClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    public WebAppClassLoader(org.eclipse.jetty.ee.webapp.WebAppClassLoader.Context context)
    {
        super(context);
    }

    public WebAppClassLoader(ClassLoader parent, org.eclipse.jetty.ee.webapp.WebAppClassLoader.Context context)
    {
        super(parent, context);
    }

    /**
     * Run an action with access to ServerClasses
     * <p>Run the passed {@link PrivilegedExceptionAction} with the classloader
     * configured so as to allow server classes to be visible</p>
     *
     * @param action The action to run
     * @param <T> the type of PrivilegedExceptionAction and the type returned by the action
     * @return The return from the action
     * @throws Exception if thrown by the action
     * @deprecated use {@link org.eclipse.jetty.ee.webapp.WebAppClassLoader#runWithHiddenClassAccess(PrivilegedExceptionAction)} instead
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public static <T> T runWithServerClassAccess(PrivilegedExceptionAction<T> action) throws Exception
    {
        return org.eclipse.jetty.ee.webapp.WebAppClassLoader.runWithHiddenClassAccess(action);
    }
}
