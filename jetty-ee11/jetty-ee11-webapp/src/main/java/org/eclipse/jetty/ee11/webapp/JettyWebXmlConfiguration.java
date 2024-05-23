//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.webapp;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks for XmlConfiguration files in WEB-INF.  Searches in order for the first of jetty8-web.xml, jetty-web.xml or web-jetty.xml
 */
public class JettyWebXmlConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebXmlConfiguration.class);

    public static final String PROPERTY_WEB_INF_URI = "web-inf.uri";
    public static final String PROPERTY_WEB_INF = "web-inf";
    public static final String XML_CONFIGURATION = "org.eclipse.jetty.webapp.JettyWebXmlConfiguration";
    public static final String JETTY_WEB_XML = "jetty-web.xml";
    public static final String JETTY_EE11_WEB_XML = "jetty-ee11-web.xml";

    public JettyWebXmlConfiguration()
    {
        super(new Builder()
            .addDependencies(WebXmlConfiguration.class, FragmentConfiguration.class, MetaInfConfiguration.class));
    }

    /**
     * Configure
     * Apply web-jetty.xml configuration
     *
     * @see Configuration#configure(WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Configuring web-jetty.xml");

        Resource webInf = context.getWebInf();
        // get the jetty-ee11-web.xml or jetty-web.xml
        Resource jetty = resolveJettyWebXml(webInf);
        if (Resources.isReadableFile(jetty))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Configure: {}", jetty);

            Object xmlAttr = context.getAttribute(XML_CONFIGURATION);
            context.removeAttribute(XML_CONFIGURATION);
            final XmlConfiguration jetty_config = xmlAttr instanceof XmlConfiguration ? (XmlConfiguration)xmlAttr : new XmlConfiguration(jetty);

            setupXmlConfiguration(context, jetty_config, webInf);

            try
            {
                WebAppClassLoader.runWithHiddenClassAccess(() ->
                {
                    jetty_config.configure(context);
                    return null;
                });
            }
            catch (Exception e)
            {
                LOG.warn("Error applying {}", jetty);
                throw e;
            }
        }
    }

    /**
     * Obtain a WEB-INF/jetty-ee9-web.xml, falling back to
     * looking for WEB-INF/jetty-web.xml.
     *
     * @param webInf the WEB-INF of the context to search
     * @return the file if it exists or null otherwise
     */
    private Resource resolveJettyWebXml(Resource webInf)
    {
        String xmlFile = JETTY_EE11_WEB_XML;
        try
        {
            if (webInf == null || !webInf.isDirectory())
                return null;

            //try to find jetty-ee11-web.xml
            Resource jetty = webInf.resolve(xmlFile);
            if (!Resources.missing(jetty))
                return jetty;

            xmlFile = JETTY_WEB_XML;
            //failing that, look for jetty-web.xml
            jetty = webInf.resolve(xmlFile);
            if (!Resources.missing(jetty))
                return jetty;

            return null;
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error resolving WEB-INF/" + xmlFile, e);
            return null;
        }
    }

    /**
     * Configures some well-known properties before the XmlConfiguration reads
     * the configuration.
     *
     * @param jettyConfig The configuration object.
     * @param webInf the WEB-INF location
     */
    private void setupXmlConfiguration(WebAppContext context, XmlConfiguration jettyConfig, Resource webInf) throws IOException
    {
        jettyConfig.setJettyStandardIdsAndProperties(context.getServer(), null);
        Map<String, String> props = jettyConfig.getProperties();
        props.put(PROPERTY_WEB_INF_URI, XmlConfiguration.normalizeURI(webInf.getURI().toString()));
        props.put(PROPERTY_WEB_INF, webInf.toString());
    }
}
