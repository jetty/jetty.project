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
 * Template to generate a simple hello wold webapp embedded in an OSGi bundle.
 */
public class HelloRFC66WebappTemplate extends AbstractJettyPDETemplateSection
{

    public static final String GREETINGS = "greetings";
    public static final String CONTEXT_PATH = "contextPath"; //$NON-NLS-1$

    public HelloRFC66WebappTemplate()
    {
        addOption(GREETINGS,"Greetings message", "Howdy!",0);
        addOption(CONTEXT_PATH,"Web-ContextPath","/hello",0); //$NON-NLS-1$
    }

    protected void internalAddPages(Wizard wizard)
    {
        WizardPage page = createPage(0, null);//no help for now
        page.setTitle("Hello World RFC66 Webapp");
        page.setDescription("Creates a webapp embedded in an OSGi bundle");
        wizard.addPage(page);
    }

    /**
     * @return 'helloRFC66Webapp' used to locate the base folder that
     * contains the template files.
     */
    public String getSectionId()
    {
        return "helloRFC66Webapp"; //$NON-NLS-1$
    }


    /**
     * Add the Web-ContextPath to the MANIFEST.MF as required by RFC66
     */
    protected void updateModel(IProgressMonitor monitor)
    {
        setManifestHeader("Web-ContextPath", String.valueOf(getOptions(0)[1].getValue())); //$NON-NLS-1$ //$NON-NLS-2$]
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.pde.ui.templates.ITemplateSection#getFoldersToInclude()
     */
     public String[] getNewFiles() {
         return new String[] {"WEB-INF/web.xml"};
     }


}
