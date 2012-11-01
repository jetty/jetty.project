//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;

public class MavenWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = Log.getLogger(MavenWebInfConfiguration.class);

    protected Resource _originalResourceBase;
    protected Resource[]  _unpackedOverlays;
  
    
    public void configure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        if (jwac.getClassPathFiles() != null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("Setting up classpath ...");

            //put the classes dir and all dependencies into the classpath
            Iterator itor = jwac.getClassPathFiles().iterator();
            while (itor.hasNext())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(((File)itor.next()).getCanonicalPath());

            //if (LOG.isDebugEnabled())
                //LOG.debug("Classpath = "+LazyList.array2List(((URLClassLoader)context.getClassLoader()).getURLs()));
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


    public void preConfigure(WebAppContext context) throws Exception
    {
        super.preConfigure(context);

    }
    
    public void postConfigure(WebAppContext context) throws Exception
    {
        super.postConfigure(context);
    }


    public void deconfigure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        
        //remove the unpacked wars
        if (_unpackedOverlays != null && _unpackedOverlays.length>0)
        {
            try
            {
                for (int i=0; i<_unpackedOverlays.length; i++)
                {
                    IO.delete(_unpackedOverlays[i].getFile());
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
        
        
        //Add in any overlays as a resource collection for the base
        _originalResourceBase = context.getBaseResource();
        JettyWebAppContext jwac = (JettyWebAppContext)context;

        //Add in any overlaid wars as base resources
        if (jwac.getOverlays() != null && !jwac.getOverlays().isEmpty())
        {
            Resource[] origResources = null;
            int origSize = 0;

            if (jwac.getBaseResource() != null)
            {
                if (jwac.getBaseResource() instanceof ResourceCollection)
                {
                    origResources = ((ResourceCollection)jwac.getBaseResource()).getResources();
                    origSize = origResources.length;
                }
                else
                {
                    origResources = new Resource[1];
                    origResources[0] = jwac.getBaseResource();
                    origSize = 1;
                }
            }
            
            int overlaySize = jwac.getOverlays().size();
            Resource[] newResources = new Resource[origSize + overlaySize];

            int offset = 0;
            if (origSize > 0)
            {
                if (jwac.getBaseAppFirst())
                {
                    System.arraycopy(origResources,0,newResources,0,origSize);
                    offset = origSize;
                }
                else
                {
                    System.arraycopy(origResources,0,newResources,overlaySize,origSize);
                }
            }
            
            // Overlays are always unpacked
            _unpackedOverlays = new Resource[overlaySize];
            List<Resource> overlays = jwac.getOverlays();
            for (int idx=0; idx<overlaySize; idx++)
            { 
                LOG.info("Unpacking overlay: " + overlays.get(idx));
                _unpackedOverlays[idx] = unpackOverlay(context, overlays.get(idx));
                 newResources[idx+offset] = _unpackedOverlays[idx];

                LOG.info("Adding overlay: " + _unpackedOverlays[idx]);
            }
            
            jwac.setBaseResource(new ResourceCollection(newResources));
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
                if (f.getName().toLowerCase().endsWith(".jar"))
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
    
    

    protected  Resource unpackOverlay (WebAppContext context, Resource overlay)
    throws IOException
    {
        //resolve if not already resolved
        resolveTempDirectory(context);
        
   
        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getName();
        if (name.endsWith("!/"))
            name = name.substring(0,name.length()-2);
        int i = name.lastIndexOf('/');
        if (i>0)
            name = name.substring(i+1,name.length());
        name = name.replace('.', '_');
        File dir = new File(context.getTempDirectory(), name);
        overlay.copyTo(dir);
        Resource unpackedOverlay = Resource.newResource(dir.getCanonicalPath());
        return  unpackedOverlay;
    }
}
