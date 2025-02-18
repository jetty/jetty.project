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

package org.eclipse.jetty.deploy.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.DeploymentScanner;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.PathCollators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A representation of all the filesystem components that are used to
 * create a {@link ContextHandler} and push it through the {@link org.eclipse.jetty.deploy.DeploymentManager}.
 */
public class PathsApp
{
    public enum State
    {
        UNCHANGED,
        ADDED,
        CHANGED,
        REMOVED
    }

    private static final Logger LOG = LoggerFactory.getLogger(PathsApp.class);
    private final String name;
    private final Map<Path, State> paths = new HashMap<>();
    private final Attributes attributes = new Attributes.Mapped();
    private State state;
    private ContextHandler contextHandler;

    public PathsApp(String name)
    {
        this.name = name;
        this.state = calcState();
    }

    private static String asStringList(Collection<Path> paths)
    {
        return paths.stream()
            .sorted(PathCollators.byName(true))
            .map(Path::toString)
            .collect(Collectors.joining(", ", "[", "]"));
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == null || getClass() != o.getClass())
            return false;
        PathsApp that = (PathsApp)o;
        return Objects.equals(name, that.name);
    }

    public Attributes getAttributes()
    {
        return this.attributes;
    }

    public ContextHandler getContextHandler()
    {
        return contextHandler;
    }

    public void setContextHandler(ContextHandler contextHandler)
    {
        this.contextHandler = contextHandler;
    }

    public Environment getEnvironment()
    {
        return (Environment)getAttributes().getAttribute(PathsContextHandlerFactory.ENVIRONMENT);
    }

    public void setEnvironment(Environment env)
    {
        getAttributes().setAttribute(PathsContextHandlerFactory.ENVIRONMENT, env);
    }

    public String getEnvironmentName()
    {
        Environment env = getEnvironment();
        if (env == null)
            return "";
        else
            return env.getName();
    }

    /**
     * Get the main path used for deployment.
     * <p>
     * Applies the heuristics reference in the main
     * javadoc for {@link DeploymentScanner}
     * </p>
     *
     * @return the main deployable path
     */
    public Path getMainPath()
    {
        List<Path> livePaths = paths
            .entrySet()
            .stream()
            .filter((e) -> e.getValue() != State.REMOVED)
            .map(Map.Entry::getKey)
            .sorted(PathCollators.byName(true))
            .toList();

        if (livePaths.isEmpty())
            return null;

        // XML always win.
        List<Path> xmls = livePaths.stream()
            .filter(FileID::isXml)
            .toList();
        if (xmls.size() == 1)
            return xmls.get(0);
        else if (xmls.size() > 1)
            throw new IllegalStateException("More than 1 XML for deployable " + asStringList(xmls));

        // WAR files are next.
        List<Path> wars = livePaths.stream()
            .filter(FileID::isWebArchive)
            .toList();
        if (wars.size() == 1)
            return wars.get(0);
        else if (wars.size() > 1)
            throw new IllegalStateException("More than 1 WAR for deployable " + asStringList(wars));

        // Directories next.
        List<Path> dirs = livePaths.stream()
            .filter(Files::isDirectory)
            .toList();
        if (dirs.size() == 1)
            return dirs.get(0);
        if (dirs.size() > 1)
            throw new IllegalStateException("More than 1 Directory for deployable " + asStringList(dirs));

        LOG.warn("Unable to determine main deployable for {}", this);
        return null;
    }

    public String getName()
    {
        return name;
    }

    public Map<Path, State> getPaths()
    {
        return paths;
    }

    public State getState()
    {
        return state;
    }

    public void setState(State state)
    {
        this.state = state;
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(name);
    }

    /**
     * Load all {@code properties} files belonging to this PathsApp
     * into its {@link Attributes}.
     *
     * @see #getAttributes()
     */
    public void loadProperties()
    {
        // look for properties file for main basename.
        String propFilename = String.format("%s.properties", getName());
        List<Path> propFiles = paths.keySet().stream()
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().equalsIgnoreCase(propFilename))
            .sorted(PathCollators.byName(true))
            .toList();

        if (propFiles.isEmpty())
        {
            // No properties file found
            return;
        }

        if (propFiles.size() > 1)
        {
            LOG.warn("Multiple matching files with name [{}]: {}", propFilename,
                asStringList(propFiles));
        }

        for (Path propFile : propFiles)
        {
            try (InputStream inputStream = Files.newInputStream(propFile))
            {
                Properties props = new Properties();
                props.load(inputStream);
                props.stringPropertyNames().forEach(
                    (name) ->
                    {
                        String value = props.getProperty(name);
                        String key = DeploymentScanner.stripOldAttributePrefix(name);
                        getAttributes().setAttribute(key, value);
                    });
            }
            catch (IOException e)
            {
                LOG.warn("Unable to read properties file: {}", propFile, e);
            }
        }

        // Look for simple old school environment name.
        String environmentName = (String)getAttributes().getAttribute(PathsContextHandlerFactory.ENVIRONMENT);
        if (StringUtil.isNotBlank(environmentName))
        {
            setEnvironment(Environment.get(environmentName));
        }
    }

    public void putPath(Path path, State state)
    {
        this.paths.put(path, state);
        setState(calcState());
    }

    public void resetStates()
    {
        // Drop paths that were removed.
        List<Path> removedPaths = paths.entrySet()
            .stream().filter(e -> e.getValue() == State.REMOVED)
            .map(Map.Entry::getKey)
            .toList();
        for (Path removedPath : removedPaths)
        {
            paths.remove(removedPath);
        }
        // Set all remaining path states to UNCHANGED
        paths.replaceAll((p, v) -> State.UNCHANGED);
        state = calcState();
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder("%s@%x".formatted(this.getClass().getSimpleName(), hashCode()));
        str.append("[").append(name);
        str.append("|").append(getState());
        str.append(", env=").append(getEnvironmentName());
        str.append(", mainPath=").append(getMainPath());
        str.append(", paths=");
        str.append(paths.entrySet().stream()
            .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
            .collect(Collectors.joining(", ", "[", "]"))
        );
        str.append(", contextHandler=");
        if (contextHandler == null)
            str.append("<unset>");
        else
            str.append(contextHandler);
        str.append("]");
        return str.toString();
    }

    /**
     * <p>
     * Calculate the State of the overall State based on the States in the Paths.
     * </p>
     * <dl>
     * <dt>UNCHANGED</dt>
     * <dd>All Path states are in UNCHANGED state</dd>
     * <dt>ADDED</dt>
     * <dd>All Path states are in ADDED state</dd>
     * <dt>CHANGED</dt>
     * <dd>At least one Path state is CHANGED, or there is a variety of states</dd>
     * <dt>REMOVED</dt>
     * <dd>All Path states are in REMOVED state, or there are no Paths being tracked</dd>
     * </dl>
     *
     * @return the state.
     */
    private State calcState()
    {
        if (paths.isEmpty())
            return State.REMOVED;

        // Calculate state of unit from Path states.
        State ret = null;
        for (State pathState : paths.values())
        {
            switch (pathState)
            {
                case UNCHANGED ->
                {
                    if (ret == null)
                        ret = State.UNCHANGED;
                    else if (ret != State.UNCHANGED)
                        ret = State.CHANGED;
                }
                case ADDED ->
                {
                    if (ret == null)
                        ret = State.ADDED;
                    else if (ret == State.UNCHANGED || ret == State.REMOVED)
                        ret = State.ADDED;
                }
                case CHANGED ->
                {
                    ret = State.CHANGED;
                }
                case REMOVED ->
                {
                    if (ret == null)
                        ret = State.REMOVED;
                    else if (ret != State.REMOVED)
                        ret = State.CHANGED;
                }
            }
        }
        return ret != null ? ret : State.UNCHANGED;
    }
}
