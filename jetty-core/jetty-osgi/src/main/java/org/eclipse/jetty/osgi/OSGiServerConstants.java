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

package org.eclipse.jetty.osgi;

/**
 * OSGiServerConstants
 *
 * Name of the properties that configure a jetty Server OSGi service.
 */
public class OSGiServerConstants
{
    /**
     * Usual system property used as the hostname for a typical jetty
     * configuration.
     */
    public static final String JETTY_HOME = "jetty.home";
    public static final String JETTY_BASE = "jetty.base";

    /**
     * System property to point to a bundle that embeds a jetty configuration
     * and that jetty configuration should be the default jetty server. First we
     * look for jetty.home. If we don't find it then we look for this property.
     */
    public static final String JETTY_HOME_BUNDLE = "jetty.home.bundle";

    /**
     * Usual system property used as the hostname for a typical jetty
     * configuration.
     */
    public static final String JETTY_HOST = "jetty.http.host";

    /**
     * Usual system property used as the port for http for a typical jetty
     * configuration.
     */
    public static final String JETTY_PORT = "jetty.http.port";

    /**
     * Usual system property used as the port for https for a typical jetty
     * configuration.
     */
    public static final String JETTY_PORT_SSL = "jetty.ssl.port";

    //for managed jetty instances, name of the configuration parameters
    /**
     * PID of the jetty servers's ManagedFactory
     */
    public static final String MANAGED_JETTY_SERVER_FACTORY_PID = "org.eclipse.jetty.ee9.osgi.boot.managedserverfactory";

    /**
     * The associated value of that configuration parameter is the name under which this
     * instance of the jetty server is tracked.
     * When a ContextHandler is deployed and it specifies the managedServerName property, it is deployed
     * on the corresponding jetty managed server or it throws an exception: jetty server not available.
     */
    public static final String MANAGED_JETTY_SERVER_NAME = "managedServerName";
    /**
     * Name of the 'default' jetty server instance.
     * Usually the first one to be created.
     */
    public static final String MANAGED_JETTY_SERVER_DEFAULT_NAME = "defaultJettyServer";

    /**
     * List of URLs to the jetty.xml files that configure th server.
     */
    public static final String MANAGED_JETTY_XML_CONFIG_URLS = "jetty.etc.config.urls";

    /**
     * List of URLs to the folders where the legacy J2EE shared libraries are stored aka lib/ext, lib/jsp etc.
     */
    public static final String MANAGED_JETTY_SHARED_LIB_FOLDER_URLS = "managedJettySharedLibFolderUrls";
    
    /*
     * Name of server attribute that stores a classloader suitable as the parent classloader for contexts
     */
    public static final String SERVER_CLASSLOADER = "org.eclipse.jetty.osgi.server.classLoader";
    
    /**
     * Name of server attribute that stores a List<Bundle> of bundles on the server classpath that must be scanned
     */
    public static final String SERVER_CLASSPATH_BUNDLES = "org.eclipse.jetty.osgi.server.classpathBundles";
    
}
