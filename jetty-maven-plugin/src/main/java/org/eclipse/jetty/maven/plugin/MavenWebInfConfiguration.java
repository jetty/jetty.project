//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;



/**
 * MavenWebInfConfiguration
 * 
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potentiall configured via the maven-war-plugin.
 *
 */
public class MavenWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = Log.getLogger(MavenWebInfConfiguration.class);

    
    protected static int COUNTER = 0; 
    protected Resource _originalResourceBase;
    protected List<Resource>  _unpackedOverlayResources;
  
    
    
    
    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void configure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        
        //put the classes dir and all dependencies into the classpath
        if (jwac.getClassPathFiles() != null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("Setting up classpath ...");
            Iterator itor = jwac.getClassPathFiles().iterator();
            while (itor.hasNext())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(((File)itor.next()).getCanonicalPath());
        }
        
        super.configure(context);
        
        // knock out environmental maven and plexus classes from webAppContext
        String[] existingServerClasses = context.getServerClasses();
        String[] newServerClasses = new String[2+(existingServerClasses==null?0:existingServerClasses.length)];
        newServerClasses[0] = "org.apache.maven.";
        newServerClasses[1] = "org.codehaus.plexus.";
        System.arraycopy( existingServerClasses, 0, newServerClasses, 2, existingServerClasses.length );
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Server classes:");
            for (int i=0;i<newServerClasses.length;i++)
                LOG.debug(newServerClasses[i]);
        }
        context.setServerClasses( newServerClasses ); 
    }

    
    

    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#preConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void preConfigure(WebAppContext context) throws Exception
    {
        super.preConfigure(context);

    }
    
    
    
    
    /** 
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#postConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void postConfigure(WebAppContext context) throws Exception
    {
        super.postConfigure(context);
    }


    
    
    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#deconfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void deconfigure(WebAppContext context) throws Exception
    {   
        //remove the unpacked wars
        if (_unpackedOverlayResources != null && !_unpackedOverlayResources.isEmpty())
        {
            try
            {
                for (Resource r:_unpackedOverlayResources)
                {
                    IO.delete(r.getFile());
                }
            }
            catch (IOException e)
            {
                LOG.ignore(e);
            }
        }
        super.deconfigure(context);
        //restore whatever the base resource was before we might have included overlaid wars
        context.setBaseResource(_originalResourceBase);
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
    }


    /**
     * Get the jars to examine from the files from which we have
     * synthesized the classpath. Note that the classpath is not
     * set at this point, so we cannot get them from the classpath.
     * @param context
     * @return the list of jars found
     */
    @Override
    protected List<Resource> findJars (WebAppContext context)
    throws Exception
    {
        List<Resource> list = new ArrayList<Resource>();
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        if (jwac.getClassPathFiles() != null)
        {
            for (File f: jwac.getClassPathFiles())
            {
                if (f.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar"))
                {
                    try
                    {
                        list.add(Resource.newResource(f.toURI()));
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            }
        }

        List<Resource> superList = super.findJars(context);
        if (superList != null)
            list.addAll(superList);
        return list;
    }
    
    
    
    

    /** 
     * Add in the classes dirs from test/classes and target/classes
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#findClassDirs(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    protected List<Resource> findClassDirs(WebAppContext context) throws Exception
    {
        List<Resource> list = new ArrayList<Resource>();
        
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        if (jwac.getClassPathFiles() != null)
        {
            for (File f: jwac.getClassPathFiles())
            {
                if (f.exists() && f.isDirectory())
                {
                    try
                    {
                        list.add(Resource.newResource(f.toURI()));
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            }
        }
        
        List<Resource> classesDirs = super.findClassDirs(context);
        if (classesDirs != null)
            list.addAll(classesDirs);
        return list;
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
