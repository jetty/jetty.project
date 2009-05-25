// ========================================================================
// Copyright (c) 2000-2009 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;


/**
 * 
 * JettyWebConfiguration.
 * 
 * Looks for Xmlconfiguration files in WEB-INF.  Searches in order for the first of jetty6-web.xml, jetty-web.xml or web-jetty.xml
 *
 * 
 *
 */
public class JettyWebXmlConfiguration implements Configuration
{
    public void preConfigure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub
        
    }

    
    /** 
     * Configure
     * Apply web-jetty.xml configuration
     * @see Configuration#configure(WebAppContext)
     */
    public void configure (WebAppContext context) throws Exception
    {
        //cannot configure if the _context is already started
        if (context.isStarted())
        {
            if (Log.isDebugEnabled()){Log.debug("Cannot configure webapp after it is started");}
            return;
        }
        
        if(Log.isDebugEnabled())
            Log.debug("Configuring web-jetty.xml");
        
        Resource web_inf = context.getWebInf();
        // handle any WEB-INF descriptors
        if(web_inf!=null&&web_inf.isDirectory())
        {
            // do jetty.xml file
            Resource jetty=web_inf.addPath("jetty6-web.xml");
            if(!jetty.exists())
                jetty=web_inf.addPath("jetty-web.xml");
            if(!jetty.exists())
                jetty=web_inf.addPath("web-jetty.xml");

            if(jetty.exists())
            {
                // No server classes while configuring 
                String[] old_server_classes = context.getServerClasses();
                try
                {
                    context.setServerClasses(null);
                    if(Log.isDebugEnabled())
                        Log.debug("Configure: "+jetty);
                    XmlConfiguration jetty_config=new XmlConfiguration(jetty.getURL());
                    jetty_config.configure(context);
                }
                finally
                {
                    if (context.getServerClasses()==null)
                        context.setServerClasses(old_server_classes);
                }
            }
        }
    }
    
    
    public void postConfigure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub
        
    }


    public void deconfigure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub
        
    } 
}
