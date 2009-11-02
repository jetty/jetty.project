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

import org.eclipse.osgi.util.NLS;

/**
 * NLS
 */
public class JettyLauncherMessages extends NLS
{
    private static final String BUNDLE_NAME = "org.eclipse.jetty.osgi.pde.launch.JettyLauncherMessages";//$NON-NLS-1$

    public static String JettyConfigurationLaunchTab_JettyConfigurationTitle;
    public static String JettyConfigurationLaunchTab_VariablesButtonLabel;
    
    public static String JettyXmlBlock_JettyConfigurationBlockTitle;
    public static String JettyXmlBlock_JettyConfigurationBlockLabel;
    public static String JettyXmlBlock_VariablesButtonLabel;
    
    public static String JettyXmlEditDialog_Cancel;
    public static String JettyXmlEditDialog_OK;
    public static String JettyXmlEditDialog_Apply;
    public static String JettyXmlEditDialog_Edit_jetty_xml;
    public static String JettyXmlEditDialog_Edit_jetty_xml_title;
    
    static {
        // load message values from bundle file
        NLS.initializeMessages(BUNDLE_NAME, JettyLauncherMessages.class);
    }


}
