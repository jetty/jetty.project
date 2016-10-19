//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
 * <p>JSP Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the org.eclipse.jetty.jsp and org.eclipse.jetty.apache packages.   
 * This class is defined in the webapp package, as it implements the {@link Configuration} interface,
 * which is unknown to the jsp package.  However, the corresponding {@link ServiceLoader}
 * resource is defined in the jsp package, so that this configuration only be 
 * loaded if the jetty-jsp jars are on the classpath.
 * </p>
 *
 */
public class JspConfiguration extends AbstractConfiguration
{
    public JspConfiguration()
    {
        beforeThis(WebXmlConfiguration.class,MetaInfConfiguration.class,WebInfConfiguration.class,FragmentConfiguration.class);
        afterThis(WebAppConfiguration.class);
        protectAndExpose("org.eclipse.jetty.jsp.");
        expose("org.eclipse.jetty.apache.");
        hide("org.eclipse.jdt.");
    }
}
