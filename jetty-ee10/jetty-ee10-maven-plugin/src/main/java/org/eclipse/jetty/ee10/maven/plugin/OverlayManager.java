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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.maven.AbstractOverlayManager;
import org.eclipse.jetty.maven.Overlay;
import org.eclipse.jetty.maven.WarPluginInfo;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * OverlayManager
 * 
 * Mediates information about overlays configured in a war plugin.
 *
 */
public class OverlayManager extends AbstractOverlayManager<MavenWebAppContext>
{

    public OverlayManager(WarPluginInfo warPlugin)
    {
        super(warPlugin);
    }

    public void applyOverlays(MavenWebAppContext webApp) throws IOException
    {
        List<Resource> resourceBases = new ArrayList<>();

        for (Overlay o : getOverlays())
        {
            //can refer to the current project in list of overlays for ordering purposes
            if (o.getConfig() != null && o.getConfig().isCurrentProject() && webApp.getBaseResource().exists())
            {
                resourceBases.add(webApp.getBaseResource());
                continue;
            }
            //add in the selectively unpacked overlay in the correct order to the webapp's resource base
            resourceBases.add(unpackOverlay(o));
        }

        if (!resourceBases.contains(webApp.getBaseResource()) && webApp.getBaseResource().exists())
        {
            if (webApp.getBaseAppFirst())
                resourceBases.add(0, webApp.getBaseResource());
            else
                resourceBases.add(webApp.getBaseResource());
        }

        webApp.setBaseResource(ResourceFactory.combine(resourceBases));
    }

}
