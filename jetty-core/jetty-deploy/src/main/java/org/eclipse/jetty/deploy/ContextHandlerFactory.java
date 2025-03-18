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

package org.eclipse.jetty.deploy;

import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.component.Environment;

/**
 * A factory to create a ContextHandler from a set of associated files.
 *
 * @see DeploymentScanner
 */
public interface ContextHandlerFactory
{
    /**
     * The attribute name for the value holding the specific class to used to create the ContextHandler
     */
    String CONTEXT_HANDLER_CLASS_ATTRIBUTE = "jetty.deploy.contextHandlerClass";
    /**
     * The attribute name for the value holding the default class to used for creating ContextHandlers in the environment
     */
    String CONTEXT_HANDLER_CLASS_DEFAULT_ATTRIBUTE = "jetty.deploy.default.contextHandlerClass";
    /**
     * The attribute name for the environment name.
     */
    String ENVIRONMENT_ATTRIBUTE = "environment";
    /**
     * Attributes that start with this name return a String pointing to XML on the filesystem that should be used for Environment based
     * modifications to the ContextHandler that was created.
     *
     * <pre>
     * jetty.deploy.environmentXml=config/base-ee8.xml
     * jetty.deploy.environmentXml.auditing=config/auditing-ee8.xml
     * </pre>
     */
    String ENVIRONMENT_XML_ATTRIBUTE = "jetty.deploy.environmentXml";
    /**
     * Attribute name that stores the {@code List<Path>} pointing to XML files that represent
     * the Environment XML files to apply to the ContextHandler has been created.
     */
    String ENVIRONMENT_XML_PATHS_ATTRIBUTE = "jetty.deploy.paths.environmentXmls";

    /**
     * @param server The server for the context.
     * @param environment The environment for the context
     * @param mainPath The {@link Path} of the main file of the context (e.g. a WAR file or XML}
     * @param otherPaths Other files associated with the context (e.g. property files)
     * @param deployAttributes Attributes describing the details of the deployment and that are made available to the
     *                         {@link org.eclipse.jetty.xml.XmlConfiguration}.  The attribute names can be <ul>
     *                             <li>Attributes defined in {@link org.eclipse.jetty.server.Deployable}</li>
     *                             <li>{@link #CONTEXT_HANDLER_CLASS_ATTRIBUTE}</li>
     *                             <li>{@link #CONTEXT_HANDLER_CLASS_DEFAULT_ATTRIBUTE}</li>
     *                             <li>{@link #ENVIRONMENT_ATTRIBUTE}</li>
     *                             <li>{@link #ENVIRONMENT_XML_ATTRIBUTE}</li>
     *                             <li>{@link #ENVIRONMENT_XML_PATHS_ATTRIBUTE}</li>
     *                         </ul>
     * @return The created {@link ContextHandler}
     * @throws Exception If there is a problem creating the {@link ContextHandler}
     */
    ContextHandler newContextHandler(Server server, Environment environment, Path mainPath, Set<Path> otherPaths, Attributes deployAttributes) throws Exception;
}
