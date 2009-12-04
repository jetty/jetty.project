// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXException;

/**
 * The information about an App that is managed by the {@link DeploymentManager}
 */
public class App
{
    private File archivePath;
    private ContextHandler handler;
    private boolean extractWars = false;
    private boolean parentLoaderPriority = false;
    private String defaultsDescriptor;
    private String originId;

    /**
     * Create an App with specified Origin ID and archivePath
     * 
     * @param originId
     *            the origin ID (The ID that the {@link AppProvider} knows about)
     * @param archivePath
     *            the path to the app (can be a file *.war or *.jar, or a webapp exploded directory)
     * @see App#getOriginId()
     * @see App#getContextId()
     */
    public App(String originId, File archivePath)
    {
        this.originId = originId;
        this.archivePath = archivePath;
    }

    /**
     * Get the archive path to the App.
     * 
     * Might exist as a Directory (example: an exploded webapp, or a jetty:run) or a File (example: a WAR file)
     * 
     * @return the
     */
    public File getArchivePath()
    {
        return this.archivePath;
    }

    /**
     * Get ContextHandler for the App.
     * 
     * Create it if needed.
     * 
     * @return the {@link ContextHandler} to use for the App when fully started. (Portions of which might be ignored
     *         when App is in the {@link AppState#STAGED} state}
     * @throws Exception
     */
    public ContextHandler getContextHandler(DeploymentManager deployMgr) throws Exception
    {
        if (handler == null)
        {
            if (FileID.isXmlFile(archivePath))
            {
                this.handler = createContextFromXml(deployMgr);
            }
            else if (FileID.isWebArchive(archivePath))
            {
                // Treat as a Web Archive.
                this.handler = createContextDefault(deployMgr);
            }
            else
            {
                throw new IllegalStateException("Not an XML or Web Archive: " + archivePath.getAbsolutePath());
            }

            this.handler.setAttributes(new AttributesMap(deployMgr.getContextAttributes()));
        }
        return handler;
    }

    private ContextHandler createContextDefault(DeploymentManager deploymgr)
    {
        String context = archivePath.getName();

        // Context Path is the same as the archive.
        if (FileID.isWebArchiveFile(archivePath))
        {
            context = context.substring(0,context.length() - 4);
        }

        // Context path is "/" in special case of archive (or dir) named "root" 
        if (context.equalsIgnoreCase("root") || context.equalsIgnoreCase("root/"))
        {
            context = URIUtil.SLASH;
        }

        // Ensure "/" is Prepended to all context paths.
        if (context.charAt(0) != '/')
        {
            context = "/" + context;
        }

        // Ensure "/" is Not Trailing in context paths.
        if (context.endsWith("/") && context.length() > 0)
        {
            context = context.substring(0,context.length() - 1);
        }

        WebAppContext wah = new WebAppContext();
        wah.setContextPath(context);
        wah.setWar(archivePath.getAbsolutePath());
        if (defaultsDescriptor != null)
        {
            wah.setDefaultsDescriptor(defaultsDescriptor);
        }
        wah.setExtractWAR(extractWars);
        wah.setParentLoaderPriority(parentLoaderPriority);

        return wah;
    }

    @SuppressWarnings("unchecked")
    private ContextHandler createContextFromXml(DeploymentManager deploymgr) throws MalformedURLException, IOException, SAXException, Exception
    {
        Resource resource = Resource.newResource(this.archivePath.toURI());
        if (!resource.exists())
        {
            return null;
        }

        XmlConfiguration xmlc = new XmlConfiguration(resource.getURL());
        Map props = new HashMap();
        props.put("Server",deploymgr.getServer());
        if (deploymgr.getConfigurationManager() != null)
        {
            props.putAll(deploymgr.getConfigurationManager().getProperties());
        }

        xmlc.setProperties(props);
        return (ContextHandler)xmlc.configure();
    }

    public boolean isExtractWars()
    {
        return extractWars;
    }

    public void setExtractWars(boolean extractWars)
    {
        this.extractWars = extractWars;
    }

    public boolean isParentLoaderPriority()
    {
        return parentLoaderPriority;
    }

    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        this.parentLoaderPriority = parentLoaderPriority;
    }

    public String getDefaultsDescriptor()
    {
        return defaultsDescriptor;
    }

    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        this.defaultsDescriptor = defaultsDescriptor;
    }

    /**
     * The unique id of the {@link App} relating to how it is installed on the jetty server side.
     * 
     * @return the generated Id for the App.
     */
    public String getContextId()
    {
        if (this.handler == null)
        {
            return null;
        }
        return this.handler.getContextPath();
    }

    /**
     * The origin of this {@link App} as specified by the {@link AppProvider}
     * 
     * @return String representing the origin of this app.
     */
    public String getOriginId()
    {
        return this.originId;
    }
}
