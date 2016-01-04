//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
    /* ------------------------------------------------------------ */
    @Override
    public void prevent(ClassLoader loader)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Pinning classloader for AppContext.getContext() with "+loader);
        ImageIO.getUseCache();
    }

}
