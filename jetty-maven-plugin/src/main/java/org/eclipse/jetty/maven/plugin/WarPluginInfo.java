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

package org.eclipse.jetty.maven.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jetty.util.StringUtil;

/**
 * WarPluginInfo
 * 
 * Information about the maven-war-plugin contained in the pom
 */
public class WarPluginInfo
{
    private MavenProject _project;
    private Plugin _plugin;
    private List<String> _dependentMavenWarIncludes;
    private List<String> _dependentMavenWarExcludes;
    private List<OverlayConfig> _overlayConfigs;
    private Set<Artifact> _warArtifacts;

    public WarPluginInfo(MavenProject project)
    {
        _project = project;
        if (_project.getArtifacts() != null)
        {
            _warArtifacts = _project.getArtifacts()
                .stream()
                .filter(a -> "war".equals(a.getType()) || "zip".equals(a.getType())).collect(Collectors.toSet());
        }
        else
            _warArtifacts = Collections.emptySet();
    }

    /**
     * @return the project
     */
    public MavenProject getProject()
    {
        return _project;
    }

    /**
     * Get all dependent artifacts that are wars.
     * @return all artifacts of type "war" or "zip"
     */
    public Set<Artifact> getWarArtifacts()
    {
        return _warArtifacts;
    }
    
    /**
     * Get an artifact of type war that matches the given coordinates.
     * @param groupId the groupId to match
     * @param artifactId the artifactId to match
     * @param classifier the classified to match
     * @return the matching Artifact or null if no match
     */
    public Artifact getWarArtifact(String groupId, String artifactId, String classifier)
    {
        Optional<Artifact> o = _warArtifacts.stream()
            .filter(a -> match(a, groupId, artifactId, classifier)).findFirst();
        return o.orElse(null);
    }

    /**
     * Find the maven-war-plugin, if one is configured
     *
     * @return the plugin
     */
    public Plugin getWarPlugin()
    {
        if (_plugin == null)
        {
            List<Plugin> plugins = _project.getBuildPlugins();
            if (plugins == null)
                return null;

            for (Plugin p : plugins)
            {
                if ("maven-war-plugin".equals(p.getArtifactId()))
                {
                    _plugin = p;
                    break;
                }
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
            getWarPlugin();

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
            getWarPlugin();

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
            getWarPlugin();

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
    
    public boolean match(Artifact a, String gid, String aid, String cls)
    {
        if (a == null)
            return (gid == null && aid == null && cls == null);
        
        if (((a.getGroupId() == null && gid == null) || (a.getGroupId() != null && a.getGroupId().equals(gid))) &&
            ((a.getArtifactId() == null && aid == null) || (a.getArtifactId() != null && a.getArtifactId().equals(aid))) &&
            ((a.getClassifier() == null) || (a.getClassifier().equals(cls))))
            return true;

        return false;
    }

    /**
     * Check if the given artifact matches the group and artifact coordinates.
     * 
     * @param a the artifact to check
     * @param gid the group id
     * @param aid the artifact id
     * @return true if matched false otherwise
     */
    public boolean match(Artifact a, String gid, String aid)
    {
        if (a == null)
            return (gid == null && aid == null);
        
        if (((a.getGroupId() == null && gid == null) || (a.getGroupId() != null && a.getGroupId().equals(gid))) &&
            ((a.getArtifactId() == null && aid == null) || (a.getArtifactId() != null && a.getArtifactId().equals(aid))))
            return true;

        return false;
    }
}
