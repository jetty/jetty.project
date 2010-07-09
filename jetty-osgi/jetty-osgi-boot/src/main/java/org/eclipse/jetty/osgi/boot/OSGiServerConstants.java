// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot;

/**
 * Name of the properties that configure a jetty Server OSGi service.
 */
public class OSGiServerConstants
{
    //for managed jetty instances, name of the configuration parameters
    /**
     * PID of the jetty servers's ManagedFactory
     */
    public static final String MANAGED_JETTY_SERVER_FACTORY_PID = "org.eclipse.jetty.osgi.boot.managedserverfactory";
    
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
    
}
