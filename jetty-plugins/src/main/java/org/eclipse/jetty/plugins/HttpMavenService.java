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
    private static final String[] GROUP_IDS = new String[]{"org/eclipse/jetty"};
    private static final String VERSION = "9.0.0-SNAPSHOT"; // TODO: should be automatically set
    private boolean _searchRemoteRepository = true;
    private boolean _searchLocalRepository = false;
    private String _localRepository = MavenUtils.getLocalRepositoryLocation();
    private String _repositoryUrl = REPOSITORY_URL;
    private String[] _groupIds = GROUP_IDS;
    private String _version = VERSION;

    public Set<String> listAvailablePlugins()
    {
        System.out.println("Using local repo: " + _searchLocalRepository + " remote repo: " + _searchRemoteRepository);
        Set<String> availablePlugins = new HashSet<>();
        if (_searchRemoteRepository)
            availablePlugins.addAll(getListOfRemotePlugins());

        if (_searchLocalRepository)
            availablePlugins.addAll(getListOfLocalPlugins());

        return availablePlugins;
    }

    private Set<String> getListOfLocalPlugins()
    {
        Set<String> availablePlugins = new HashSet<>();
        File localMavenRepository = new File(_localRepository);
        if (!localMavenRepository.exists())
        {
            System.out.println("Can't find local repo: " + localMavenRepository);
            return availablePlugins;
        }

        System.out.println("Using local repository: " + localMavenRepository);

        for (String groupId : _groupIds)
        {
            File file = new File(_localRepository + groupId);
            if (!file.exists())
                break;

            String[] localMavenModuleList = file.list();
            for (String potentialPlugin : localMavenModuleList)
            {
                File pluginFile = new File(_localRepository + getPluginPath(groupId,potentialPlugin));
                if (pluginFile.exists())
                    availablePlugins.add(potentialPlugin);
            }
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
            StringBuilder directoryListing = new StringBuilder();
            for (String groupId : _groupIds)
            {
                URL url = new URL(_repositoryUrl + groupId);
                URLConnection connection = url.openConnection();
                InputStream inputStream = connection.getInputStream();
                directoryListing.append(StreamUtils.inputStreamToString(inputStream));
            }
            return directoryListing.toString();
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private String fetchModuleDirectoryListing(String module)
    {
        for (String groupId : _groupIds)
        {
            try
            {
                URL configJar = new URL(_repositoryUrl + getModulePath(groupId, module));
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
                System.out.println(e); //TODO:
            }
        }
        return "not a plugin";
    }

    public Plugin getPlugin(String pluginName)
    {
        File pluginJar = getPluginFile(pluginName);
        return new Plugin(pluginName, pluginJar);
    }

    private String getPluginPath(String groupId, String pluginName)
    {
        return getModulePath(groupId, pluginName) + pluginName + "-" + _version + "-plugin.zip";
    }

    private String getModulePath(String groupId, String pluginName)
    {
        return groupId + "/" + pluginName + "/" + _version
                + "/";
    }

    /**
     * Tries to find the plugin in the local repo first and then tries the remote repositories in the order they're
     * stored in _repositoryUrls
     *
     * @param pluginName the name of the plugin to get the plugin file for
     * @return the plugin file
     */
    private File getPluginFile(String pluginName)
    {
        for (String groupId : _groupIds)
        {
            File pluginFile = new File(MavenUtils.getLocalRepositoryLocation() + getPluginPath(groupId, pluginName));
            if (pluginFile.exists())
                return pluginFile;

            String urlString = _repositoryUrl + getPluginPath(groupId, pluginName);
            String fileName = urlString.substring(urlString.lastIndexOf("/") + 1);
            try
            {
                return getPluginFileFromRemoteLocation(urlString, fileName);
            }
            catch (IOException e)
            {
                System.out.println("Couldn't find plugin: " + pluginName + " at repo: " + _repositoryUrl + ". " +
                        "Probably trying other repo. Reason: " + e.getMessage());
            }
        }
        throw new IllegalStateException("Plugin: " + pluginName + "  not found at any configured repo.");
    }

    private File getPluginFileFromRemoteLocation(String urlString, String fileName) throws IOException
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

    public void setGroupId(String groupId)
    {
        this._groupIds = new String[] { groupId.replace(".", "/") };
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
