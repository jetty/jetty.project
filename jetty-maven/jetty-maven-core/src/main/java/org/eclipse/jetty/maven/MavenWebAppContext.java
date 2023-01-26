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

package org.eclipse.jetty.maven;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.resource.CombinedResource;

/**
 * MavenWebAppContext
 *
 * Extends the WebAppContext to specialize for the maven environment. We pass in
 * the list of files that should form the classpath for the webapp when
 * executing in the plugin, and any jetty-env.xml file that may have been
 * configured.
 */
public class MavenWebAppContext
{
    private File _classes = null;

    private File _testClasses = null;

    private final List<File> _webInfClasses = new ArrayList<>();

    private final List<File> _webInfJars = new ArrayList<>();

    private final Map<String, File> _webInfJarMap = new HashMap<>();

    private List<URI> _classpathUris; // webInfClasses+testClasses+webInfJars

    private String _jettyEnvXml;

    private List<Overlay> _overlays;

    private String war;

    private String extraClasspath;

    /**
     * Set the "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern" with
     * a pattern for matching jars on container classpath to scan. This is
     * analogous to the WebAppContext.setAttribute() call.
     */
    private String _containerIncludeJarPattern = null;

    /**
     * Set the "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern" with a
     * pattern for matching jars on webapp's classpath to scan. This is
     * analogous to the WebAppContext.setAttribute() call.
     */
    private String _webInfIncludeJarPattern = null;

    private String originAttribute;

    /**
     * If there is no maven-war-plugin config for ordering of the current
     * project in the sequence of overlays, use this to control whether the
     * current project is added first or last in list of overlaid resources
     */
    private boolean _baseAppFirst = true;

    private String[] resourceBases;

    public MavenWebAppContext()
    {
        super();
    }

    public void setContainerIncludeJarPattern(String pattern)
    {
        _containerIncludeJarPattern = pattern;
    }

    public String getContainerIncludeJarPattern()
    {
        return _containerIncludeJarPattern;
    }

    public String getWebInfIncludeJarPattern()
    {
        return _webInfIncludeJarPattern;
    }

    public void setWebInfIncludeJarPattern(String pattern)
    {
        _webInfIncludeJarPattern = pattern;
    }

    public List<URI> getClassPathUris()
    {
        return this._classpathUris;
    }

    public void setJettyEnvXml(String jettyEnvXml)
    {
        this._jettyEnvXml = jettyEnvXml;
    }

    public String getJettyEnvXml()
    {
        return this._jettyEnvXml;
    }

    public void setClasses(File dir)
    {
        _classes = dir;
    }

    public File getClasses()
    {
        return _classes;
    }

    public void setWebInfLib(List<File> jars)
    {
        _webInfJars.addAll(jars);
    }

    public void setTestClasses(File dir)
    {
        _testClasses = dir;
    }

    public File getTestClasses()
    {
        return _testClasses;
    }

    /**
     * Ordered list of wars to overlay on top of the current project. The list
     * may contain an overlay that represents the current project.
     *
     * @param overlays the list of overlays
     */
    public void setOverlays(List<Overlay> overlays)
    {
        _overlays = overlays;
    }

    /**
     * Set the name of the attribute that is used in each generated xml element
     * to indicate the source of the xml element (eg annotation, web.xml etc).
     *
     * @param name the name of the attribute to use.
     */
    public void setOriginAttribute(String name)
    {
        this.originAttribute = name;
    }

    /**
     * @return the originAttribute
     */
    public String getOriginAttribute()
    {
        return this.originAttribute;
    }

    public List<Overlay> getOverlays()
    {
        return _overlays;
    }

    public void setBaseAppFirst(boolean value)
    {
        _baseAppFirst = value;
    }

    public boolean getBaseAppFirst()
    {
        return _baseAppFirst;
    }

    /**
     * This method is provided as a convenience for jetty maven plugin
     * configuration
     *
     * @param resourceBases Array of resources strings to set as a
     * {@link CombinedResource}.
     */
    public void setResourceBases(String[] resourceBases)
    {
        this.resourceBases = resourceBases;
    }

    public List<File> getWebInfLib()
    {
        return _webInfJars;
    }

    public List<File> getWebInfClasses()
    {
        return _webInfClasses;
    }

    public String getWar()
    {
        return war;
    }

    public void setWar(String war)
    {
        this.war = war;
    }

    public String[] getResourceBases()
    {
        return resourceBases;
    }

    public String getExtraClasspath()
    {
        return extraClasspath;
    }

    public void setExtraClasspath(String extraClasspath)
    {
        this.extraClasspath = extraClasspath;
    }
}
