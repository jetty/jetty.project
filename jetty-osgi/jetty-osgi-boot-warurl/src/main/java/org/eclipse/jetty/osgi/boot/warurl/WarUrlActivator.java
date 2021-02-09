//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.boot.warurl;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;

/**
 * Register the factory to handle the war scheme specified by rfc66
 * when the bundle is activated.
 */
public class WarUrlActivator implements BundleActivator
{

    private ServiceRegistration _reg;

    /**
     * Register the url stream handler factory.
     *
     * @param context the {@link BundleContext} to use
     */
    @SuppressWarnings("unchecked")
    @Override
    public void start(BundleContext context) throws Exception
    {
        Dictionary props = new Hashtable();
        props.put(URLConstants.URL_HANDLER_PROTOCOL, new String[]{"war"});
        context.registerService(URLStreamHandlerService.class.getName(),
            new WarUrlStreamHandler(), props);
    }

    /**
     * Remove the url stream handler. (probably not required,
     * as osgi might shutdown every registered service
     * by default: need test)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        try
        {
            if (_reg != null)
            {
                _reg.unregister();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
