// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


package org.eclipse.jetty.webapp;

import java.util.List;

import org.eclipse.jetty.util.resource.Resource;

/**
 * FragmentConfiguration
 * 
 * This configuration supports some Servlet 3.0 features in jetty-7. 
 * 
 * Process web-fragments in jars
 */
public class FragmentConfiguration implements Configuration
{
    public final static String FRAGMENT_RESOURCES="org.eclipse.jetty.webFragments";
    
    public void preConfigure(WebAppContext context) throws Exception
    {
        if (!context.isConfigurationDiscovered())
            return;
        
        MetaData processor = (MetaData)context.getAttribute(MetaData.METADATA); 
        if (processor == null)
        {
            processor = new MetaData (context);
            context.setAttribute(MetaData.METADATA, processor);
        }
      
        //parse web-fragment.xmls
        parseWebFragments(context, processor);
        
    }
    
    public void configure(WebAppContext context) throws Exception
    { 
        if (!context.isConfigurationDiscovered())
            return;
        
        MetaData metaData = (MetaData)context.getAttribute(MetaData.METADATA); 
        if (metaData == null)
        {
            metaData = new MetaData (context);
            context.setAttribute(MetaData.METADATA, metaData);
        }
        
        //order the fragments first
        metaData.orderFragments(); 
          
        //process the fragments
        metaData.processFragments(); 
    }

    public void deconfigure(WebAppContext context) throws Exception
    {
       
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(FRAGMENT_RESOURCES, null);
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Look for any web.xml fragments in META-INF of jars in WEB-INF/lib
     * 
     * @throws Exception
     */
    public void parseWebFragments (final WebAppContext context, final MetaData processor) throws Exception
    {
        List<Resource> frags = (List<Resource>)context.getAttribute(FRAGMENT_RESOURCES);
        if (frags!=null)
        {
            for (Resource frag : frags)
            {
                processor.parseFragment(Resource.newResource("jar:"+frag.getURL()+"!/META-INF/web-fragment.xml"));
            }
        }
    }
}
