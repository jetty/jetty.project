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

package org.eclipse.jetty.server;

import java.io.File;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;

/**
 * Interface that can be implemented by a {@link ContextHandler}
 * to allow configuration to be passed from a {@code Deployer} without
 * dependencies on the jetty-deploy module itself.
 */
public interface Deployable
{
    Pattern EE_ENVIRONMENT_NAME_PATTERN = Pattern.compile("ee(\\d+)");

    /**
     * A comparator that ranks names matching EE_ENVIRONMENT_NAME_PATTERN higher than other names,
     * EE names are compared by EE number, otherwise simple name comparison is used.
     */
    Comparator<String> ENVIRONMENT_COMPARATOR = (e1, e2) ->
    {
        Matcher m1 = EE_ENVIRONMENT_NAME_PATTERN.matcher(e1);
        Matcher m2 = EE_ENVIRONMENT_NAME_PATTERN.matcher(e2);

        if (m1.matches())
        {
            if (m2.matches())
            {
                int n1 = Integer.parseInt(m1.group(1));
                int n2 = Integer.parseInt(m2.group(1));
                return Integer.compare(n1, n2);
            }
            return 1;
        }
        if (m2.matches())
            return -1;

        return e1.compareTo(e2);
    };

    /**
     * <p>Attribute key name: Temp Directory for context.</p>
     *
     * <p>Value can be a {@link File}, {@code String}, or {@link java.nio.file.Path}</p>
     *
     * @see ContextHandler#setTempDirectory(File)
     */
    String TEMP_DIR = "jetty.deploy.tempDir";
    /**
     * <p>Attribute key name: The Configuration Classes for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a {@code String[]} (String Array)</p>
     */
    String CONFIGURATION_CLASSES = "jetty.deploy.configurationClasses";
    /**
     * <p>Attribute key name: The Container Scan Jar Pattern for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a regex {@code String}</p>
     */
    String CONTAINER_SCAN_JARS = "jetty.deploy.containerScanJarPattern";
    /**
     * <p>Attribute key name: Specifies the context-path of the {@link ContextHandler}</p>
     *
     * <p>Value is a {@code String}</p>
     *
     * @see ContextHandler#setContextPath(String)
     */
    String CONTEXT_PATH = "jetty.deploy.contextPath";
    /**
     * <p>Attribute key name: Specifies the context-path of the {@link ContextHandler}</p>
     *
     * <p>Value is a {@code String}</p>
     *
     * @see ContextHandler#setContextPath(String)
     */
    String DEFAULT_CONTEXT_PATH = "jetty.deploy.defaultContextPath";
    /**
     * <p>Attribute key name: Specifies the default descriptor to user for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a {@code String} pointing to a filesystem path</p>
     */
    String DEFAULTS_DESCRIPTOR = "jetty.deploy.defaultsDescriptor";
    /**
     * <p>Attribute key name: Specifies the flag to extract/unpack a WAR file for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a {@link Boolean}</p>
     */
    String EXTRACT_WARS = "jetty.deploy.extractWars";
    /**
     * <p>Attribute key name: Specifies the Parent ClassLoader Priority for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a {@link Boolean}</p>
     */
    String PARENT_LOADER_PRIORITY = "jetty.deploy.parentLoaderPriority";
    /**
     * <p>Attribute key name: Specifies the Servlet Container Initializer Exclusion Pattern for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a regex {@code String}</p>
     */
    String SCI_EXCLUSION_PATTERN = "jetty.deploy.servletContainerInitializerExclusionPattern";
    /**
     * <p>Attribute key name: Specifies the Servlet Container Initializer Ordering for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a comma-delimited {@code String}</p>
     *
     * @see "ServletContainerInitializerOrdering in EE specific package for details on syntax"
     */
    String SCI_ORDER = "jetty.deploy.servletContainerInitializerOrder";
    /**
     * <p>Attribute key name: Specifies the WAR file (if relevant) of the deployable for EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a {@code String} pointing to a filesystem path</p>
     */
    String WAR = "jetty.deploy.war";
    /**
     * <p>Attribute key name: Specifies the pattern of Jars in {@code WEB-INF/lib} to scan for annotations in EE based deployments.</p>
     *
     * <p>Non-EE deployments will not use this configuration.</p>
     *
     * <p>Value is a regex {@code String}</p>
     */
    String WEBINF_SCAN_JARS = "jetty.deploy.webInfScanJarPattern";
    /**
     * <p>Attribute key name: Specifies the main {@link java.nio.file.Path} that is being deployed.</p>
     *
     * <p>Value is a {@link java.nio.file.Path}</p>
     */
    String MAIN_PATH = "jetty.deploy.paths.main";
    /**
     * <p>Attribute key name: Specifies the list of other {@link java.nio.file.Path} that are relevant to the deployment.</p>
     *
     * <p>Value is a {@link java.util.Collection} of {@link java.nio.file.Path} instances</p>
     */
    String OTHER_PATHS = "jetty.deploy.paths.other";

    void initializeDefaults(Attributes attributes);
}
