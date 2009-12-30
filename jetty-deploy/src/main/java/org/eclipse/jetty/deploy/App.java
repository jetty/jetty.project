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
    private final AppProvider _provider;
    private final String _originId;
    private final File _archivePath;
    private ContextHandler _context;

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
    public App(DeploymentManager manager, AppProvider provider, String originId, File archivePath)
    {
        _manager=manager;
        _provider = provider;
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

    /* ------------------------------------------------------------ */
    /**
     * @return The AppProvider
     */
    public AppProvider getAppProvider()
    {
        return _provider;
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
            _context=getAppProvider().createContextHandler(this);
            this._context.setAttributes(new AttributesMap(_manager.getContextAttributes()));
        }
        return _context;
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
    
    public String toString()
    {
        return "App["+_context+","+_originId+","+_archivePath+"]";
    }
}
