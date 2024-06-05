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

package org.eclipse.jetty.ee;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.ClassMatcher;
import org.eclipse.jetty.util.component.Environment;

/**
 * Common attributes and methods for configuring the {@link ClassLoader Class loading} of web application:
 * <ul>
 *     <li>Protected (a.k.a. System) classes are classes typically provided by the JVM, that cannot be replaced by the
 *         web application, and they are always loaded via the environment or system classloader.  They are visible but
 *         protected.</li>
 *     <li>Hidden (a.k.a. Server) classes are those used to implement the Server and are not made available to the
 *         web application. They are hidden from the web application {@link ClassLoader}.</li>
 * </ul>
 * <p>These protections are set to reasonable defaults {@link #DEFAULT_PROTECTED_CLASSES} and {@link #DEFAULT_HIDDEN_CLASSES},
 * which may be programmatically configured and will affect the defaults applied to all web applications in the same JVM.
 *
 * <p>
 * The defaults applied by a specific {@link Server} can be configured using {@link #addProtectedClasses(Server, String...)} and
 * {@link #addHiddenClasses(Server, String...)}. Alternately the {@link Server} attributes {@link #PROTECTED_CLASSES_ATTRIBUTE}
 * and {@link #HIDDEN_CLASSES_ATTRIBUTE} may be used to direct set a {@link ClassMatcher} to use for all web applications
 * within the server instance.
 * </p>
 * <p>
 * The defaults applied by a specific {@link Environment} can be configured using {@link #addProtectedClasses(Environment, String...)} and
 * {@link #addHiddenClasses(Environment, String...)}. Alternately the {@link Environment} attributes {@link #PROTECTED_CLASSES_ATTRIBUTE}
 * and {@link #HIDDEN_CLASSES_ATTRIBUTE} may be used to direct set a {@link ClassMatcher} to use for all web applications
 * within the server instance.
 * </p>
 * <p>
 * Ultimately, the configurations set by this class only affects the defaults applied to each web application 
 * {@link org.eclipse.jetty.server.handler.ContextHandler Context} and the {@link ClassMatcher} fields of the web applications
 * can be directly access to configure a specific context.
 * </p>
 */
public class WebAppClassLoading
{
    public static final String PROTECTED_CLASSES_ATTRIBUTE = "org.eclipse.jetty.webapp.systemClasses";
    public static final String HIDDEN_CLASSES_ATTRIBUTE = "org.eclipse.jetty.webapp.serverClasses";

    /**
     * The default protected (system) classes used by a web application, which will be applied to the {@link ClassMatcher}s created
     * by {@link #getProtectedClasses(Environment)}.
     */
    public static final ClassMatcher DEFAULT_PROTECTED_CLASSES = new ClassMatcher(
        "java.",                            // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "javax.",                           // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "jakarta.",                         // Jakarta classes (per servlet spec v5.0 / Section 15.2.1)
        "org.xml.",                         // javax.xml
        "org.w3c."                          // javax.xml
    );

    /**
     * The default hidden (server) classes used by a web application, which can be applied to the {@link ClassMatcher}s created
     * by {@link #getHiddenClasses(Environment)}.
     */
    public static final ClassMatcher DEFAULT_HIDDEN_CLASSES = new ClassMatcher(
        "org.eclipse.jetty."                // hide jetty classes
    );

    /**
     * Get the default protected (system) classes for a {@link Server}
     * @param server The {@link Server} for the defaults
     * @return The default protected (system) classes for the {@link Server}, which will be empty if not previously configured.
     */
    public static ClassMatcher getProtectedClasses(Server server)
    {
        return getClassMatcher(server, PROTECTED_CLASSES_ATTRIBUTE, null);
    }

    /**
     * Get the default protected (system) classes for an {@link Environment}
     * @param environment The {@link Server} for the defaults
     * @return The default protected (system) classes for the {@link Environment}, which will be the {@link #DEFAULT_PROTECTED_CLASSES} if not previously configured.
     */
    public static ClassMatcher getProtectedClasses(Environment environment)
    {
        return getClassMatcher(environment, PROTECTED_CLASSES_ATTRIBUTE, DEFAULT_PROTECTED_CLASSES);
    }

    /**
     * Add a protected (system) Class pattern to use for all WebAppContexts.
     * @param patterns the patterns to use
     */
    public static void addProtectedClasses(String... patterns)
    {
        DEFAULT_PROTECTED_CLASSES.add(patterns);
    }

    /**
     * Add a protected (system) Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param attributes The {@link Attributes} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addProtectedClasses(Attributes attributes, String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            getClassMatcher(attributes, PROTECTED_CLASSES_ATTRIBUTE, null).add(patterns);
    }

    /**
     * Add a protected (system) Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param server The {@link Server} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addProtectedClasses(Server server, String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            getClassMatcher(server, PROTECTED_CLASSES_ATTRIBUTE, null).add(patterns);
    }

    /**
     * Add a protected (system) Class pattern to use for WebAppContexts of a given environment.
     * @param environment The {@link Environment} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addProtectedClasses(Environment environment, String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            getClassMatcher(environment, PROTECTED_CLASSES_ATTRIBUTE, DEFAULT_PROTECTED_CLASSES).add(patterns);
    }
    
    /**
     * Get the default hidden (server) classes for a {@link Server}
     * @param server The {@link Server} for the defaults
     * @return The default hidden (server) classes for the {@link Server}, which will be empty if not previously configured.
     *
     */
    public static ClassMatcher getHiddenClasses(Server server)
    {
        return getClassMatcher(server, HIDDEN_CLASSES_ATTRIBUTE, null);
    }

    /**
     * Get the default hidden (server) classes for an {@link Environment}
     * @param environment The {@link Server} for the defaults
     * @return The default hidden (server) classes for the {@link Environment}, which will be {@link #DEFAULT_PROTECTED_CLASSES} if not previously configured.
     */
    public static ClassMatcher getHiddenClasses(Environment environment)
    {
        return getClassMatcher(environment, HIDDEN_CLASSES_ATTRIBUTE, DEFAULT_HIDDEN_CLASSES);
    }

    /**
     * Add a hidden (server) Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param patterns the patterns to use
     */
    public static void addHiddenClasses(String... patterns)
    {
        DEFAULT_HIDDEN_CLASSES.add(patterns);
    }

    /**
     * Add a hidden (server) Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param attributes The {@link Attributes} instance to add classes to
     * @param patterns the patterns to use
     * @deprecated use {@link #addHiddenClasses(Server, String...)} instead
     */
    @Deprecated (since = "12.0.9", forRemoval = true)
    public static void addHiddenClasses(Attributes attributes, String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            getClassMatcher(attributes, HIDDEN_CLASSES_ATTRIBUTE, null).add(patterns);
    }

    /**
     * Add a hidden (server) Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param server The {@link Server} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addHiddenClasses(Server server, String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            getClassMatcher(server, HIDDEN_CLASSES_ATTRIBUTE, null).add(patterns);
    }

    /**
     * Add a hidden (server) Class pattern to use for all ee9 WebAppContexts.
     * @param environment The {@link Environment} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addHiddenClasses(Environment environment, String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            getClassMatcher(environment, HIDDEN_CLASSES_ATTRIBUTE, DEFAULT_HIDDEN_CLASSES).add(patterns);
    }

    private static ClassMatcher getClassMatcher(Attributes attributes, String attribute, ClassMatcher defaultPatterns)
    {
        Object existing = attributes.getAttribute(attribute);
        if (existing instanceof ClassMatcher cm)
            return cm;

        ClassMatcher classMatcher = (existing instanceof String[] stringArray)
            ? new ClassMatcher(stringArray) : new ClassMatcher(defaultPatterns);
        attributes.setAttribute(attribute, classMatcher);
        return classMatcher;
    }

}
