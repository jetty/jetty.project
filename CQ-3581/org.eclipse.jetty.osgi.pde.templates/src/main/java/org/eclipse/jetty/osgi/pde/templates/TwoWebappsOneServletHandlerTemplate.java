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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;

/**
 * Template that generates jetty webapps.
 */
public class TwoWebappsOneServletHandlerTemplate extends AbstractJettyPDETemplateSection
{

    public static final String GREETINGS = "greetings"; //$NON-NLS-1$
    public static final String CONTEXT_PATH_APP_1 = "contextPathApp1"; //$NON-NLS-1$
    public static final String CONTEXT_PATH_APP_2 = "contextPathApp2"; //$NON-NLS-1$

    public TwoWebappsOneServletHandlerTemplate()
    {
        addOption(GREETINGS,"Greetings message", "Howdy!",0);
        addOption(CONTEXT_PATH_APP_1,"Webapp 1 Context Path","/app1",0); //$NON-NLS-1$
        addOption(CONTEXT_PATH_APP_2,"Webapp 2 Context Path","/app2",0); //$NON-NLS-1$
    }

    public void internalAddPages(Wizard wizard)
    {
        WizardPage page = createPage(0, null);
        page.setTitle("Two webapps and one servlet in an OSGi bundle");
        page.setDescription("Creates multiple jetty webapps embedded in an OSGi bundle");
        wizard.addPage(page);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.pde.ui.templates.OptionTemplateSection#getSectionId()
     */
    public String getSectionId()
    {
        return "twoWebappsOneServletHandler"; //$NON-NLS-1$
    }


    /**
     * Add the Jetty-ContextFilePath to the MANIFEST.MF as required for
     * jetty-osgi to be able to identify the embedded webapps.
     */
    protected void updateModel(IProgressMonitor monitor)
    {
        setManifestHeader("Jetty-ContextFilePath",
        		"jettycontexts/myservlet.xml, " + //$NON-NLS-1$ //$NON-NLS-2$
        		"jettycontexts/app1.xml, jettycontexts/app2.xml"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.pde.ui.templates.ITemplateSection#getFoldersToInclude()
     */
     public String[] getNewFiles() {
         return new String[] {"war1/WEB-INF/web.xml",
        		 "war2/WEB-INF/web.xml", //$NON-NLS-1$ //$NON-NLS-2$
                 "jettycontexts/app1.xml", "jettycontexts/app2.xml", //$NON-NLS-1$ //$NON-NLS-2$
                 "jettycontexts/myservlet.xml"}; //$NON-NLS-1$
     }

}
