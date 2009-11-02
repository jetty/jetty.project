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
package org.eclipse.jetty.osgi.pde.templates;

import org.eclipse.pde.ui.IFieldData;
import org.eclipse.pde.ui.templates.NewPluginTemplateWizard;

/**
 * Base template wizard: set the Import-Packages header in the generated MANIFEST.MF
 * to import the javax.servlet and javax.servlet.http
 */
public abstract class AbstractOSGiWebappNewWizard extends NewPluginTemplateWizard
{
    /* (non-Javadoc)
     * @see org.eclipse.pde.ui.templates.AbstractNewPluginTemplateWizard#init(org.eclipse.pde.ui.IFieldData)
     */
    public void init(IFieldData data)
    {
        super.init(data);
        setWindowTitle("Webapp(s) embedded in an OSGi bundle");
    }
    
    /**
     * In these default examples, the only dependency are the servlet's packages.
     */
    public String[] getImportPackages()
    {
        return new String[] {"javax.servlet;version=\"2.5.0\"", //$NON-NLS-1$
                "javax.servlet.http;version=\"2.5.0\""}; //$NON-NLS-1$
    }

}
