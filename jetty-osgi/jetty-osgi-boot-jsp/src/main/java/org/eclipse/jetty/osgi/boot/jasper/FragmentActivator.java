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
// ========================================================================
package org.eclipse.jetty.osgi.boot.jasper;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Pseudo fragment activator.
 * Called by the main org.eclipse.jetty.osgi.boot bundle.
 * Please note: this is not a real BundleActivator. Simply something called back by
 * the host bundle.
 */
public class FragmentActivator implements BundleActivator
{
    /**
     * 
     */
    public void start(BundleContext context) throws Exception {
        System.err.println("hello from jasper boot fragment");
    }

    /**
     * 
     */
    public void stop(BundleContext context) throws Exception {
        
    }
}
