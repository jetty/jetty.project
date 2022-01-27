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

import javax.imageio.ImageIO;

/**
 * AppContextLeakPreventer
 *
 * Cause the classloader that is pinned by AppContext.getAppContext() to be
 * a container or system classloader, not a webapp classloader.
 *
 * Inspired by Tomcat JreMemoryLeakPrevention.
 */
public class AppContextLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Pinning classloader for AppContext.getContext() with{} ", loader);
        ImageIO.getUseCache();
    }
}
