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
package org.eclipse.jetty.osgi.pde.launch;

/**
 * Configuration constants.
 */
public class JettyConfigurationConstants
{
    private static final String PLUGIN_ID = JettyOSGiPDEPlugin.PLUGIN_ID;
    
    /** Points to the jetty home folder */
    public static final String ATTR_JETTY_HOME = PLUGIN_ID + ".jettyhome";
    public static final String JETTY_HOME_DEFAULT = "${workspace_loc}/jettyhome";
    
}
