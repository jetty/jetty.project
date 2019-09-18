//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin;

import org.eclipse.jetty.quickstart.QuickStartConfiguration;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * MavenQuickStartConfiguration
 */
public class MavenQuickStartConfiguration extends QuickStartConfiguration
{
    private static final Logger LOG = Log.getLogger(QuickStartConfiguration.class);
    
    @Override
    public Class<? extends Configuration> replaces()
    {
        return QuickStartConfiguration.class;
    }
    
    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        //if we're not persisting the temp dir, get rid of any overlays
        if (!context.isPersistTempDirectory())
        {
            Resource originalBases = (Resource)context.getAttribute("org.eclipse.jetty.resources.originalBases");
            String originalBaseStr = originalBases.toString();

            //Iterate over all of the resource bases and ignore any that were original bases, just
            //deleting the overlays
            Resource res = context.getBaseResource();
            if (res instanceof ResourceCollection)
            {
                for (Resource r : ((ResourceCollection)res).getResources())
                {
                    if (originalBaseStr.contains(r.toString()))
                        continue;
                    IO.delete(r.getFile());
                }
            }
        }
        super.deconfigure(context);
    }
}
