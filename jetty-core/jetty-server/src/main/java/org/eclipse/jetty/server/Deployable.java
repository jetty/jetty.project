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

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interface that can be implemented by ContextHandlers within Environments to allow configuration
 * to be passed from the DeploymentManager without dependencies on the Deployment module itself. 
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

    String ATTRIBUTE_PREFIX = "jetty.deploy.attribute.";
    String TEMP_DIR = "jetty.deploy.tempDir";
    String CONFIGURATION_CLASSES = "jetty.deploy.configurationClasses";
    String CONTAINER_SCAN_JARS = "jetty.deploy.containerScanJarPattern";
    String CONTEXT_PATH = "jetty.deploy.contextPath";
    String DEFAULTS_DESCRIPTOR = "jetty.deploy.defaultsDescriptor";
    String ENVIRONMENT = "environment";
    String EXTRACT_WARS = "jetty.deploy.extractWars";
    String PARENT_LOADER_PRIORITY = "jetty.deploy.parentLoaderPriority";
    String SCI_EXCLUSION_PATTERN = "jetty.deploy.servletContainerInitializerExclusionPattern";
    String SCI_ORDER = "jetty.deploy.servletContainerInitializerOrder";
    String WAR = "jetty.deploy.war";
    String WEBINF_SCAN_JARS = "jetty.deploy.webInfScanJarPattern";

    void initializeDefaults(Map<String, String> properties);
}
