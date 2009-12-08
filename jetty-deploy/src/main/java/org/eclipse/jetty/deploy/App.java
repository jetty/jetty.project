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
    private final DeploymentManager _manager;
    private final String _originId;
    private final File _archivePath;
    private ContextHandler _context;
    private boolean _extractWars = false;
    private boolean _parentLoaderPriority = false;
    private String _defaultsDescriptor;

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
    public App(DeploymentManager manager, String originId, File archivePath)
    {
        _manager=manager;
        _originId = originId;
        _archivePath = archivePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The deployment manager
     */
    public DeploymentManager getDeploymentManager()
    {
        return _manager;
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
        return this._archivePath;
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
    public ContextHandler getContextHandler() throws Exception
    {
        if (_context == null)
        {
            if (FileID.isXmlFile(_archivePath))
            {
                this._context = createContextFromXml(_manager);
            }
            else if (FileID.isWebArchive(_archivePath))
            {
                // Treat as a Web Archive.
                this._context = createContextDefault(_manager);
            }
            else
            {
                throw new IllegalStateException("Not an XML or Web Archive: " + _archivePath.getAbsolutePath());
            }

            this._context.setAttributes(new AttributesMap(_manager.getContextAttributes()));
        }
        return _context;
    }

    private ContextHandler createContextDefault(DeploymentManager deploymgr)
    {
        String context = _archivePath.getName();

        // Context Path is the same as the archive.
        if (FileID.isWebArchiveFile(_archivePath))
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
        wah.setWar(_archivePath.getAbsolutePath());
        if (_defaultsDescriptor != null)
        {
            wah.setDefaultsDescriptor(_defaultsDescriptor);
        }
        wah.setExtractWAR(_extractWars);
        wah.setParentLoaderPriority(_parentLoaderPriority);

        return wah;
    }

    @SuppressWarnings("unchecked")
    private ContextHandler createContextFromXml(DeploymentManager deploymgr) throws MalformedURLException, IOException, SAXException, Exception
    {
        Resource resource = Resource.newResource(this._archivePath.toURI());
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
        return _extractWars;
    }

    public void setExtractWars(boolean extractWars)
    {
        this._extractWars = extractWars;
    }

    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        this._parentLoaderPriority = parentLoaderPriority;
    }

    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        this._defaultsDescriptor = defaultsDescriptor;
    }

    /**
     * The unique id of the {@link App} relating to how it is installed on the jetty server side.
     * 
     * @return the generated Id for the App.
     */
    public String getContextId()
    {
        if (this._context == null)
        {
            return null;
        }
        return this._context.getContextPath();
    }

    /**
     * The origin of this {@link App} as specified by the {@link AppProvider}
     * 
     * @return String representing the origin of this app.
     */
    public String getOriginId()
    {
        return this._originId;
    }
}
