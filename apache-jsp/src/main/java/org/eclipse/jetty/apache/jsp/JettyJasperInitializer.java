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

package org.eclipse.jetty.apache.jsp;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletContext;

import org.apache.jasper.servlet.JasperInitializer;
import org.apache.jasper.servlet.TldPreScanned;
import org.apache.jasper.servlet.TldScanner;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.xml.sax.SAXException;

/**
 * JettyJasperInitializer
 *
 */
public class JettyJasperInitializer extends JasperInitializer
{
   private static final Logger LOG = Log.getLogger(JettyJasperInitializer.class);
    
    /**
     * NullTldScanner
     *
     * Does nothing. Used when we can tell that all jsps have been precompiled, in which case
     * the tlds are not needed.
     */
    private final class NullTldScanner extends TldScanner
    {
        /**
         * @param context
         * @param namespaceAware
         * @param validation
         * @param blockExternal
         */
        private NullTldScanner(ServletContext context, boolean namespaceAware, boolean validation, boolean blockExternal)
        {
            super(context, namespaceAware, validation, blockExternal);
        }

        /**
         * @see org.apache.jasper.servlet.TldScanner#scan()
         */
        @Override
        public void scan() throws IOException, SAXException
        {
            return; //do nothing
        }

        /**
         * @see org.apache.jasper.servlet.TldScanner#getListeners()
         */
        @Override
        public List<String> getListeners()
        {
            return Collections.emptyList();
        }

        /**
         * @see org.apache.jasper.servlet.TldScanner#scanJars()
         */
        @Override
        public void scanJars()
        {
           return; //do nothing
        }
    }

    /**
     * Make a TldScanner, and prefeed it the tlds that have already been discovered in jar files
     * by the MetaInfConfiguration.
     * 
     * @see org.apache.jasper.servlet.JasperInitializer#prepareScanner(javax.servlet.ServletContext, boolean, boolean, boolean)
     */
    @Override
    public TldScanner newTldScanner(ServletContext context, boolean namespaceAware, boolean validate, boolean blockExternal)
    {  
        String tmp = context.getInitParameter("org.eclipse.jetty.jsp.precompiled");
        if (tmp!=null && !tmp.equals("") && Boolean.valueOf(tmp))
        {
            if (LOG.isDebugEnabled()) LOG.debug("Jsp precompilation detected");
            return new NullTldScanner(context, namespaceAware, validate, blockExternal);
        }
        
        Collection<URL> tldUrls = (Collection<URL>)context.getAttribute("org.eclipse.jetty.tlds");
        if (tldUrls != null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("Tld pre-scan detected");
            return new TldPreScanned(context,namespaceAware,validate,blockExternal,tldUrls);
        }
        
        if (LOG.isDebugEnabled()) LOG.debug("Defaulting to jasper tld scanning");
        return super.newTldScanner(context, namespaceAware, validate, blockExternal);
    }
    

}
