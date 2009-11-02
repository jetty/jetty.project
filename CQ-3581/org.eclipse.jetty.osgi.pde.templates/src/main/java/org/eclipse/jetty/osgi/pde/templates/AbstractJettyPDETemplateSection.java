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

import java.net.URL;
import java.util.ResourceBundle;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.pde.core.plugin.IPluginReference;
import org.eclipse.pde.ui.templates.OptionTemplateSection;

/**
 * Simple abstract implementation of OptionTemplateSection suitable for templates defined
 * in this bundle.
 */
public abstract class AbstractJettyPDETemplateSection extends OptionTemplateSection
{
	
	public AbstractJettyPDETemplateSection()
	{
		super.setPageCount(getNumberOfPagesToCreateCount());
	}
	
    /**
     * @return The location of the installation of this current bundle.
     */
    protected URL getInstallURL() {
        return JettyProjectTemplateActivator.getDefault().getBundle().getEntry("/");
    }
    
    /**
     * @return The properties files for this bundle.
     */
    protected ResourceBundle getPluginResourceBundle() {
        return Platform.getResourceBundle(
        		JettyProjectTemplateActivator.getDefault().getBundle());
    }
    
    /**
     * Calls internalAddPages.
     * Takes care of calling markPagesAdded as required by the super implementation.
     */
	public final void addPages(Wizard wizard)
	{
		super.markPagesAdded();
	}
	
	/**
	 * Actually add the pages to the wizard here.
	 * @param wizard
	 */
	protected abstract void internalAddPages(Wizard wizard);
    
	/**
	 * @return the number of pages that will be added here. By default 1.
	 */
	protected int getNumberOfPagesToCreateCount()
	{
		return 1;
	}
	
    /**
     * @return null By default we don't declare extension points.
     * This is for OSGi bundles not eclipse plugins.
     */
    public String getUsedExtensionPoint()
    {
        return null;
    }

    /**
     * @return true: inherit from parent wizard
     */
    public boolean isDependentOnParentWizard()
    {
        return true;
    }

    /**
     * @return 1 by default we don't not do anything fancy with the progress monitors.
     */
    public int getNumberOfWorkUnits()
    {
        return super.getNumberOfWorkUnits() + 1;
    }

    /**
     * @return false by default we don't generate multiple projects at once right now.
     */
    public IPluginReference[] getDependencies(String schemaVersion)
    {
        return new IPluginReference[0];
    }
    
    
}
