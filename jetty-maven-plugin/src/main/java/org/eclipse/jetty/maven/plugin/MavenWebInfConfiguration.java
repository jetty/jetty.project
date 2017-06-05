//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;


/**
 * MavenWebInfConfiguration
 * 
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potential configured via the maven-war-plugin.
 */
public class MavenWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = Log.getLogger(MavenWebInfConfiguration.class);

    public static final String RESOURCE_BASES_POST_OVERLAY = "org.eclipse.jetty.resource.postOverlay";
    protected static int COUNTER = 0; 
    protected Resource _originalResourceBase;
    protected List<Resource>  _unpackedOverlayResources;

    public MavenWebInfConfiguration()
    {
        hide("org.apache.maven.",
             "org.codehaus.plexus.");
    }

    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebInfConfiguration.class;
    }
    
    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void configure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        
        //put the classes dir and all dependencies into the classpath
        if (jwac.getClassPathFiles() != null && context.getClassLoader() instanceof WebAppClassLoader)
        {
            if (LOG.isDebugEnabled()) 
                LOG.debug("Setting up classpath ...");
            WebAppClassLoader loader=(WebAppClassLoader)context.getClassLoader();
            for (File classpath:jwac.getClassPathFiles())
                loader.addClassPath(classpath.getCanonicalPath());
        }
        
        super.configure(context);
    }

    
    
    
    
    
    
    
    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#deconfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void deconfigure(WebAppContext context) throws Exception
    {   
        super.deconfigure(context);
        //restore whatever the base resource was before we might have included overlaid wars
        context.setBaseResource(_originalResourceBase);
        //undo the setting of the overlayed resources
        context.removeAttribute(RESOURCE_BASES_POST_OVERLAY);
    }

    
    

    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#unpack(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void unpack(WebAppContext context) throws IOException
    {
        //Unpack and find base resource as normal
        super.unpack(context);
        
        //Get the base resource for the "virtual" webapp
        _originalResourceBase = context.getBaseResource();

        JettyWebAppContext jwac = (JettyWebAppContext)context;

        //determine sequencing of overlays
        _unpackedOverlayResources = new ArrayList<Resource>();

       

        if (jwac.getOverlays() != null && !jwac.getOverlays().isEmpty())
        {
            List<Resource> resourceBaseCollection = new ArrayList<Resource>();

            for (Overlay o:jwac.getOverlays())
            {
                //can refer to the current project in list of overlays for ordering purposes
                if (o.getConfig() != null && o.getConfig().isCurrentProject() && _originalResourceBase.exists())
                {
                    resourceBaseCollection.add(_originalResourceBase); 
                    LOG.debug("Adding virtual project to resource base list");
                    continue;
                }

                Resource unpacked = unpackOverlay(jwac,o);
                _unpackedOverlayResources.add(unpacked); //remember the unpacked overlays for later so we can delete the tmp files
                resourceBaseCollection.add(unpacked); //add in the selectively unpacked overlay in the correct order to the webapps resource base
                LOG.debug("Adding "+unpacked+" to resource base list");
            }

            if (!resourceBaseCollection.contains(_originalResourceBase) && _originalResourceBase.exists())
            {
                if (jwac.getBaseAppFirst())
                {
                    LOG.debug("Adding virtual project first in resource base list");
                    resourceBaseCollection.add(0, _originalResourceBase);
                }
                else
                {
                    LOG.debug("Adding virtual project last in resource base list");
                    resourceBaseCollection.add(_originalResourceBase);
                }
            }
            jwac.setBaseResource(new ResourceCollection(resourceBaseCollection.toArray(new Resource[resourceBaseCollection.size()])));
        }
        
        jwac.setAttribute(RESOURCE_BASES_POST_OVERLAY, jwac.getBaseResource());
    }


    protected  Resource unpackOverlay (WebAppContext context, Overlay overlay)
    throws IOException
    {
        LOG.debug("Unpacking overlay: " + overlay);
        
        if (overlay.getResource() == null)
            return null; //nothing to unpack
   
        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getResource().getName();
        if (name.endsWith("!/"))
            name = name.substring(0,name.length()-2);
        int i = name.lastIndexOf('/');
        if (i>0)
            name = name.substring(i+1,name.length());
        name = name.replace('.', '_');
        name = name+(++COUNTER); //add some digits to ensure uniqueness
        File dir = new File(context.getTempDirectory(), name); 
        
        //if specified targetPath, unpack to that subdir instead
        File unpackDir = dir;
        if (overlay.getConfig() != null && overlay.getConfig().getTargetPath() != null)
            unpackDir = new File (dir, overlay.getConfig().getTargetPath());
        
        overlay.getResource().copyTo(unpackDir);
        //use top level of unpacked content
        Resource unpackedOverlay = Resource.newResource(dir.getCanonicalPath());
        
        LOG.debug("Unpacked overlay: "+overlay+" to "+unpackedOverlay);
        return  unpackedOverlay;
    }
}
