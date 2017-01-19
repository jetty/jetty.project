//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import java.util.ServiceLoader;

/**
 * <p>Websocket Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the org.eclipse.jetty.websocket package.   
 * This class is defined in the webapp package, as it implements the {@link Configuration} interface,
 * which is unknown to the websocket package.  However, the corresponding {@link ServiceLoader}
 * resource is defined in the websocket package, so that this configuration only be 
 * loaded if the jetty-websocket jars are on the classpath.
 * </p>
 */
public class WebSocketConfiguration extends AbstractConfiguration
{
    public WebSocketConfiguration()
    {
        beforeThis(WebXmlConfiguration.class,MetaInfConfiguration.class,WebInfConfiguration.class,FragmentConfiguration.class);
        afterThis("org.eclipse.jetty.annotations.AnnotationConfiguration",WebAppConfiguration.class.getName());
        protectAndExpose("org.eclipse.jetty.websocket.");
    }
}
