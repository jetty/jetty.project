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

package org.eclipse.jetty.ee;

import java.util.Map;

public interface Deployable
{
    static String BASE_TEMP_DIR = "jetty.deploy.tempDir";
    static String CONFIGURATION_CLASSES = "jetty.deploy.configurationClasses";
    static String CONTAINER_SCAN_JARS = "jetty.deploy.containerIncludeJarPattern";
    static String DEFAULT_DESCRIPTOR = "jetty.deploy.defaultsDescriptor";
    static String ENVIRONMENT_SCAN_JARS = "jetty.deploy.environmentIncludeJarPattern";
    static String EXTRACT_WARS = "jetty.deploy.extractWars";
    static String PARENT_LOADER_PRIORITY = "jetty.deploy.parentLoaderPriority";
    static String SCI_EXCLUSION_PATTERN = "jetty.deploy.servletContainerInitializerExclusionPattern";
    static String SCI_ORDER = "jetty.deploy.servletContainerInitializerOrder";
    static String WEBINF_SCAN_JARS = "jetty.deploy.webInfIncludeJarPattern";

    void initializeDefaults(Map<String, String> properties);
}
