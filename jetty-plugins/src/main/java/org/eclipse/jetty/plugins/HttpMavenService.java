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

package org.eclipse.jetty.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.plugins.model.Plugin;
import org.eclipse.jetty.plugins.util.MavenUtils;
import org.eclipse.jetty.plugins.util.RepositoryParser;
import org.eclipse.jetty.plugins.util.StreamUtils;

public class HttpMavenService implements MavenService
{
    private static final String REPOSITORY_URL = "http://repo2.maven.org/maven2/";
    // autodetect...without maven deps
    private static final String GROUP_ID = "org/eclipse/jetty";
    private static final String VERSION = "9.0.0-SNAPSHOT"; // TODO: should be automatically set
    private boolean _searchRemoteRepository = true;
    private boolean _searchLocalRepository = false;
    private String _localRepository = MavenUtils.getLocalRepositoryLocation();
    private String _repositoryUrl = REPOSITORY_URL;
    private String _groupId = GROUP_ID;
    private String _version = VERSION;

    public Set<String> listAvailablePlugins()
    {
        System.out.println("Using local repo: " + _searchLocalRepository + " remote repo: " + _searchRemoteRepository);
        Set<String> availablePlugins = new HashSet<>();
        if(_searchRemoteRepository)
            availablePlugins.addAll(getListOfRemotePlugins());

        if(_searchLocalRepository)
            availablePlugins.addAll(getListOfLocalPlugins());

        return availablePlugins;
    }

    private Set<String> getListOfLocalPlugins()
    {
        Set<String> availablePlugins = new HashSet<>();
        File localMavenRepository = new File(_localRepository + _groupId);
        if(!localMavenRepository.exists())
        {
            System.out.println("Can't find local repo: " + localMavenRepository);
            return availablePlugins;
        }

        System.out.println("Using local repository: " + localMavenRepository);
        String[] localMavenModuleList = localMavenRepository.list();

        for (String potentialPlugin : localMavenModuleList)
        {
            File pluginFile = new File(_localRepository + getPluginPath(potentialPlugin));
            if(pluginFile.exists())
                availablePlugins.add(potentialPlugin);
        }

        return availablePlugins;
    }

    private Set<String> getListOfRemotePlugins()
    {
        Set<String> availablePlugins = new HashSet<>();

        String moduleListing = fetchDirectoryListingOfJettyModules();
        List<String> modules = RepositoryParser
                .parseLinksInDirectoryListing(moduleListing);

        for (String module : modules)
        {
            String listing = fetchModuleDirectoryListing(module);
            if (RepositoryParser.isModuleAPlugin(listing))
            {
                availablePlugins.add(module);
            }
        }
        return availablePlugins;
    }

    private String fetchDirectoryListingOfJettyModules()
    {
        try
        {
            URL url = new URL(_repositoryUrl + _groupId);
            URLConnection connection = url.openConnection();
            InputStream inputStream = connection.getInputStream();
            return StreamUtils.inputStreamToString(inputStream);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private String fetchModuleDirectoryListing(String module)
    {
        try
        {
            URL configJar = new URL(getRemoteModuleDirectory(module));
            URLConnection connection = configJar.openConnection();
            InputStream inputStream = connection.getInputStream();
            return StreamUtils.inputStreamToString(inputStream);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalStateException(e);
        }
        catch (IOException e)
        {
            // Honestly, I'm not a friend of ignoring exceptions as it might
            // hide something important. In this case however it "usually"
            // just means: THIS IS NOT A PLUGIN! However it still might hide
            // things. If that'll be the case, I hope I'm not the one who
            // has to debug my own code. ;)
            return "not a plugin";
        }
    }

    public Plugin getPlugin(String pluginName)
    {
        File configJar = getFile(getRemotePluginLocation(pluginName));
        return new Plugin(pluginName, configJar);
    }

    private String getRemoteModuleDirectory(String pluginName)
    {
        return _repositoryUrl + getModulePath(pluginName);
    }

    private String getRemotePluginLocation(String pluginName)
    {
        return _repositoryUrl + getPluginPath(pluginName);
    }

    private String getPluginPath(String pluginName)
    {
        return getModulePath(pluginName) + pluginName + "-" +  _version + "-plugin.zip";
    }

    private String getModulePath(String pluginName)
    {
        return _groupId + "/" + pluginName + "/" + _version
                + "/";
    }

    private File getFile(String urlString)
    {
        String fileName = urlString.substring(urlString.lastIndexOf("/") + 1);
        try
        {
            URL url = new URL(urlString);
            URLConnection connection = url.openConnection();
            InputStream inputStream = connection.getInputStream();
            File tempFile = new File(System.getProperty("java.io.tmpdir"),
                    fileName);
            OutputStream out = new FileOutputStream(tempFile);
            byte buf[] = new byte[1024];
            int len;
            while ((len = inputStream.read(buf)) > 0)
                out.write(buf, 0, len);
            out.close();
            inputStream.close();
            return tempFile;
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    public void setGroupId(String groupId)
    {
        this._groupId = groupId.replace(".", "/");
    }

    public void setLocalRepository(String localRepository)
    {
        this._localRepository = localRepository;
    }

    public void setRepositoryUrl(String repositoryUrl)
    {
        this._repositoryUrl = repositoryUrl;
    }

    public void setVersion(String version)
    {
        this._version = version;
    }

    public void setSearchRemoteRepository(boolean searchRemoteRepository)
    {
        this._searchRemoteRepository = searchRemoteRepository;
    }

    public void setSearchLocalRepository(boolean searchLocalRepository)
    {
        this._searchLocalRepository = searchLocalRepository;
    }
}
