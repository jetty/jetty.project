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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.eclipse.jetty.util.resource.Resource;

/**
 * OverlayManager
 * 
 * Mediates information about overlays configured in a war plugin.
 *
 */
public class OverlayManager
{
    private WarPluginInfo warPlugin;

    public OverlayManager(WarPluginInfo warPlugin)
    {
        this.warPlugin = warPlugin;
    }

    public void applyOverlays(MavenWebAppContext webApp)
        throws Exception
    {
        List<Resource> resourceBases = new ArrayList<Resource>();

        for (Overlay o : getOverlays())
        {
            //can refer to the current project in list of overlays for ordering purposes
            if (o.getConfig() != null && o.getConfig().isCurrentProject() && webApp.getResourceBase().exists())
            {
                resourceBases.add(webApp.getResourceBase()); 
                continue;
            }
            //add in the selectively unpacked overlay in the correct order to the webapp's resource base
            resourceBases.add(unpackOverlay(o));
        }

        if (!resourceBases.contains(webApp.getResourceBase()) && webApp.getResourceBase().exists())
        {
            if (webApp.getBaseAppFirst())
                resourceBases.add(0, webApp.getResourceBase());
            else
                resourceBases.add(webApp.getResourceBase());
        }

        // TODO: need a better place to close/release this mount.
        Resource.Mount mount = Resource.mountCollection(resourceBases);
        webApp.addBean(mount); // let context clean it up
        webApp.setBaseResource(mount.root());
    }
    
    /**
     * Generate an ordered list of overlays
     */
    protected List<Overlay> getOverlays()
        throws Exception
    {        
        Set<Artifact> matchedWarArtifacts = new HashSet<Artifact>();
        List<Overlay> overlays = new ArrayList<Overlay>();
        
        //Check all of the overlay configurations
        for (OverlayConfig config:warPlugin.getMavenWarOverlayConfigs())
        {
            //overlays can be individually skipped
            if (config.isSkip())
                continue;

            //an empty overlay refers to the current project - important for ordering
            if (config.isCurrentProject())
            {
                Overlay overlay = new Overlay(config, null);
                overlays.add(overlay);
                continue;
            }

            //if a war matches an overlay config
            Artifact a = warPlugin.getWarArtifact(config.getGroupId(), config.getArtifactId(), config.getClassifier());
            if (a != null)
            {
                matchedWarArtifacts.add(a);
                Resource.Mount mount = Resource.mountJar(a.getFile().toPath());
                SelectiveJarResource r = new SelectiveJarResource(mount.root());
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                Overlay overlay = new Overlay(config, r);
                overlays.add(overlay);
            }
        }

        //iterate over the left over war artifacts add them
        for (Artifact a: warPlugin.getWarArtifacts())
        {
            if (!matchedWarArtifacts.contains(a))
            {
                Resource.Mount mount = Resource.mountJar(a.getFile().toPath());
                Overlay overlay = new Overlay(null, mount.root());
                overlays.add(overlay);
            }
        }
        return overlays;
    }
    
    /**
     * Unpack a war overlay.
     * 
     * @param overlay the war overlay to unpack
     * @return the location to which it was unpacked
     * @throws IOException
     */
    protected  Resource unpackOverlay(Overlay overlay)
        throws IOException
    {        
        if (overlay.getResource() == null)
            return null; //nothing to unpack

        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getResource().getName();
        if (name.endsWith("!/"))
            name = name.substring(0, name.length() - 2);
        int i = name.lastIndexOf('/');
        if (i > 0)
            name = name.substring(i + 1, name.length());
        name = name.replace('.', '_');
 
        File overlaysDir = new File(warPlugin.getProject().getBuild().getDirectory(), "jetty_overlays");
        File dir = new File(overlaysDir, name);

        //if specified targetPath, unpack to that subdir instead
        File unpackDir = dir;
        if (overlay.getConfig() != null && overlay.getConfig().getTargetPath() != null)
            unpackDir = new File(dir, overlay.getConfig().getTargetPath());

        overlay.unpackTo(unpackDir);
        
        //use top level of unpacked content
        return Resource.newResource(unpackDir.getCanonicalPath());
    }
}
