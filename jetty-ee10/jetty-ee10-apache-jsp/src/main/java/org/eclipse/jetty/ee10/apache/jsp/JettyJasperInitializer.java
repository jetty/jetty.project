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

package org.eclipse.jetty.ee10.apache.jsp;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.ServletContext;
import org.apache.jasper.servlet.JasperInitializer;
import org.apache.jasper.servlet.TldScanner;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.xml.sax.SAXException;

/**
 * JettyJasperInitializer
 */
public class JettyJasperInitializer extends JasperInitializer
{
    private static final Log LOG = LogFactory.getLog(JasperInitializer.class);

    /**
     * NullTldScanner
     *
     * Does nothing. Used when we can tell that all jsps have been precompiled, in which case
     * the tlds are not needed.
     */
    private final class NullTldScanner extends TldScanner
    {
        /**
         *
         */
        private NullTldScanner(ServletContext context, boolean namespaceAware, boolean validation, boolean blockExternal)
        {
            super(context, namespaceAware, validation, blockExternal);
        }

        @Override
        public void scan() throws IOException, SAXException
        {
            return; //do nothing
        }

        @Override
        public List<String> getListeners()
        {
            return Collections.emptyList();
        }

        @Override
        public void scanJars()
        {
            return; //do nothing
        }
    }

    /**
     * Make a TldScanner, and prefeed it the tlds that have already been discovered in jar files
     * by the MetaInfConfiguration.
     */
    @Override
    public TldScanner newTldScanner(ServletContext context, boolean namespaceAware, boolean validate, boolean blockExternal)
    {
        String tmp = context.getInitParameter("org.eclipse.jetty.jsp.precompiled");
        if (tmp != null && !tmp.equals("") && Boolean.valueOf(tmp))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Jsp precompilation detected");
            return new NullTldScanner(context, namespaceAware, validate, blockExternal);
        }

        Collection<URL> tldUrls = (Collection<URL>)context.getAttribute("org.eclipse.jetty.tlds");
        if (tldUrls != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Tld pre-scan detected");
            return new JettyTldPreScanned(context, namespaceAware, validate, blockExternal, tldUrls);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Defaulting to jasper tld scanning");
        return super.newTldScanner(context, namespaceAware, validate, blockExternal);
    }
}
