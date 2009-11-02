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
package org.eclipse.jetty.osgi.pde.launch.ui;

/**
 * Object that can read the value of the osgi.config.area property that will be
 * used when the OSGi app is launched. 
 */
public interface IConfigurationAreaSettingHolder
{
    /**
     * @return the string entered in the settings tab that defines the
     * value o osgi.config.area.
     */
    public String getConfigurationAreaLocation();

}
