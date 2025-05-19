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

import java.io.IOException;

/**
 * A WebAppClassLoader that caches {@link #getResource(String)} results.
 * Specifically this ClassLoader caches not found classes and resources,
 * which can greatly increase performance for applications that search
 * for resources.
 *
 * @deprecated use core {@link org.eclipse.jetty.ee.webapp.CachingWebAppClassLoader} instead
 */
@Deprecated(since = "12.1.0", forRemoval = true)
public class CachingWebAppClassLoader extends org.eclipse.jetty.ee.webapp.CachingWebAppClassLoader
{
    public CachingWebAppClassLoader(ClassLoader parent, Context context) throws IOException
    {
        super(parent, context);
    }

    public CachingWebAppClassLoader(Context context) throws IOException
    {
        super(context);
    }
}
