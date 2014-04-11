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

package org.eclipse.jetty.webapp;


import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * MetaInfConfiguration
 *
 * Scan META-INF of all jars in WEB-INF/lib to find:
 * <ul>
 * <li>tlds
 * <li>web-fragment.xml
 * <li>resources
 * </ul>
 */
public class MetaInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(MetaInfConfiguration.class);

    public static final String METAINF_TLDS = TagLibConfiguration.TLD_RESOURCES;
    public static final String METAINF_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES;
    public static final String METAINF_RESOURCES = WebInfConfiguration.RESOURCE_URLS;
  
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
       //Merge all container and webinf lib jars to look for META-INF resources
      
        ArrayList<Resource> jars = new ArrayList<Resource>();
        jars.addAll(context.getMetaData().getOrderedContainerJars());
        jars.addAll(context.getMetaData().getWebInfJars());
        
        JarScanner scanner = new JarScanner()
        {
            public void processEntry(URI jarUri, JarEntry entry)
            {
                try
                {
                    MetaInfConfiguration.this.processEntry(context,jarUri,entry);
                }
                catch (Exception e)
                {
                    LOG.warn("Problem processing jar entry " + entry, e);
                }
            }
        };
        
        
        //Scan jars for META-INF information
        if (jars != null)
        {
            URI[] uris = new URI[jars.size()];
            int i=0;
            for (Resource r : jars)
            {
                uris[i++] = r.getURI();
            }
            scanner.scan(null, uris, true);
        }
    }
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
 
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(METAINF_FRAGMENTS, null); 
        context.setAttribute(METAINF_RESOURCES, null);
        context.setAttribute(METAINF_TLDS, null);
    }

    public void addResource (WebAppContext context, String attribute, Resource jar)
    {
        @SuppressWarnings("unchecked")
        List<Resource> list = (List<Resource>)context.getAttribute(attribute);
        if (list==null)
        {
            list=new ArrayList<Resource>();
            context.setAttribute(attribute,list);
        }
        if (!list.contains(jar))
            list.add(jar);
    }
    
    
    protected void processEntry(WebAppContext context, URI jarUri, JarEntry entry)
    {
        String name = entry.getName();

        if (!name.startsWith("META-INF/"))
            return;
        
        try
        {
            if (name.equals("META-INF/web-fragment.xml") && context.isConfigurationDiscovered())
            {
                addResource(context,METAINF_FRAGMENTS,Resource.newResource(jarUri));     
            }
            else if (name.equals("META-INF/resources/") && context.isConfigurationDiscovered())
            {
                addResource(context,METAINF_RESOURCES,Resource.newResource("jar:"+jarUri+"!/META-INF/resources"));
            }
            else
            {
                String lcname = name.toLowerCase(Locale.ENGLISH);
                if (lcname.endsWith(".tld"))
                {
                    addResource(context,METAINF_TLDS,Resource.newResource("jar:"+jarUri+"!/"+name));
                }
            }
        }
        catch(Exception e)
        {
            context.getServletContext().log(jarUri+"!/"+name,e);
        }
    }
}
