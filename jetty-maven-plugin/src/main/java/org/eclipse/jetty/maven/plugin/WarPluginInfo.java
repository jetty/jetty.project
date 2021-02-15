//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.maven.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jetty.util.StringUtil;

/**
 * WarPluginInfo
 * <p>
 * Information about the maven-war-plugin contained in the pom
 */
public class WarPluginInfo
{
    private MavenProject _project;
    private Plugin _plugin;
    private List<String> _dependentMavenWarIncludes;
    private List<String> _dependentMavenWarExcludes;
    private List<OverlayConfig> _overlayConfigs;

    public WarPluginInfo(MavenProject project)
    {
        _project = project;
    }

    /**
     * Find the maven-war-plugin, if one is configured
     *
     * @return the plugin
     */
    public Plugin getPlugin()
    {
        if (_plugin == null)
        {
            List plugins = _project.getBuildPlugins();
            if (plugins == null)
                return null;

            Iterator itor = plugins.iterator();
            while (itor.hasNext() && _plugin == null)
            {
                Plugin plugin = (Plugin)itor.next();
                if ("maven-war-plugin".equals(plugin.getArtifactId()))
                    _plugin = plugin;
            }
        }
        return _plugin;
    }

    /**
     * Get value of dependentWarIncludes for maven-war-plugin
     *
     * @return the list of dependent war includes
     */
    public List<String> getDependentMavenWarIncludes()
    {
        if (_dependentMavenWarIncludes == null)
        {
            getPlugin();

            if (_plugin == null)
                return null;

            Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
            if (node == null)
                return null;

            node = node.getChild("dependentWarIncludes");
            if (node == null)
                return null;
            String val = node.getValue();
            _dependentMavenWarIncludes = StringUtil.csvSplit(null, val, 0, val.length());
        }
        return _dependentMavenWarIncludes;
    }

    /**
     * Get value of dependentWarExcludes for maven-war-plugin
     *
     * @return the list of dependent war excludes
     */
    public List<String> getDependentMavenWarExcludes()
    {
        if (_dependentMavenWarExcludes == null)
        {
            getPlugin();

            if (_plugin == null)
                return null;

            Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
            if (node == null)
                return null;

            node = node.getChild("dependentWarExcludes");
            if (node == null)
                return null;
            String val = node.getValue();
            _dependentMavenWarExcludes = StringUtil.csvSplit(null, val, 0, val.length());
        }
        return _dependentMavenWarExcludes;
    }

    /**
     * Get config for any overlays that have been declared for the maven-war-plugin.
     *
     * @return the list of overlay configs
     */
    public List<OverlayConfig> getMavenWarOverlayConfigs()
    {
        if (_overlayConfigs == null)
        {
            getPlugin();

            if (_plugin == null)
                return Collections.emptyList();

            getDependentMavenWarIncludes();
            getDependentMavenWarExcludes();

            Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
            if (node == null)
                return Collections.emptyList();

            node = node.getChild("overlays");
            if (node == null)
                return Collections.emptyList();

            Xpp3Dom[] nodes = node.getChildren("overlay");
            if (nodes == null)
                return Collections.emptyList();

            _overlayConfigs = new ArrayList<OverlayConfig>();
            for (int i = 0; i < nodes.length; i++)
            {
                OverlayConfig overlayConfig = new OverlayConfig(nodes[i], _dependentMavenWarIncludes, _dependentMavenWarExcludes);
                _overlayConfigs.add(overlayConfig);
            }
        }

        return _overlayConfigs;
    }

    /**
     * @return the xml as a string
     */
    public String getMavenWarOverlayConfigAsString()
    {
        getPlugin();

        if (_plugin == null)
            return "";

        Xpp3Dom node = (Xpp3Dom)_plugin.getConfiguration();
        if (node == null)
            return "";
        return node.toString();
    }
}
