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

package org.eclipse.jetty.jndi.java;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.naming.spi.ObjectFactory;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/** 
 * javaURLContextFactory
 * <p>
 * This is the URL context factory for the <code>java:</code> URL.
 */
public class javaURLContextFactory implements ObjectFactory
{
    private static final Logger LOG = Log.getLogger(javaURLContextFactory.class);

    /**
     * Either return a new context or the resolution of a url.
     *
     * @param url an <code>Object</code> value
     * @param name a <code>Name</code> value
     * @param ctx a <code>Context</code> value
     * @param env a <code>Hashtable</code> value
     * @return a new context or the resolved object for the url
     * @exception Exception if an error occurs
     */
    public Object getObjectInstance(Object url, Name name, Context ctx, Hashtable env)
        throws Exception
    {
        // null object means return a root context for doing resolutions
        if (url == null)
        {
            if(LOG.isDebugEnabled())LOG.debug(">>> new root context requested ");
            return new javaRootURLContext(env);
        }

        // return the resolution of the url
        if (url instanceof String)
        {
            if(LOG.isDebugEnabled())LOG.debug(">>> resolution of url "+url+" requested");
            Context rootctx = new javaRootURLContext (env);
            return rootctx.lookup ((String)url);
        }

        // return the resolution of at least one of the urls
        if (url instanceof String[])
        {
            if(LOG.isDebugEnabled())LOG.debug(">>> resolution of array of urls requested");
            String[] urls = (String[])url;
            Context rootctx = new javaRootURLContext (env);
            Object object = null;
            NamingException e = null;
            for (int i=0;(i< urls.length) && (object == null); i++)
            {
                try
                {
                    object = rootctx.lookup (urls[i]);
                }
                catch (NamingException x)
                {
                    e = x;
                }
            }

            if (object == null)
                throw e;
            else
                return object;
        }

        if(LOG.isDebugEnabled())LOG.debug(">>> No idea what to do, so return a new root context anyway");
        return new javaRootURLContext (env);
    }
};
