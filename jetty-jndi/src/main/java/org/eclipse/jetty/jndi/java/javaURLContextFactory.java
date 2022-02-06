//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.jndi.java;

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.naming.spi.ObjectFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This is a required name for JNDI
// @checkstyle-disable-check : TypeNameCheck

/**
 * javaURLContextFactory
 * <p>
 * This is the URL context factory for the <code>java:</code> URL.
 */
public class javaURLContextFactory implements ObjectFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(javaURLContextFactory.class);

    /**
     * Either return a new context or the resolution of a url.
     *
     * @param url an <code>Object</code> value
     * @param name a <code>Name</code> value
     * @param ctx a <code>Context</code> value
     * @param env a <code>Hashtable</code> value
     * @return a new context or the resolved object for the url
     * @throws Exception if an error occurs
     */
    @Override
    public Object getObjectInstance(Object url, Name name, Context ctx, Hashtable env)
        throws Exception
    {
        // null object means return a root context for doing resolutions
        if (url == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(">>> new root context requested ");
            return new javaRootURLContext(env);
        }

        // return the resolution of the url
        if (url instanceof String)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(">>> resolution of url {} requested", url);
            Context rootctx = new javaRootURLContext(env);
            return rootctx.lookup((String)url);
        }

        // return the resolution of at least one of the urls
        if (url instanceof String[])
        {
            if (LOG.isDebugEnabled())
                LOG.debug(">>> resolution of array of urls requested");
            String[] urls = (String[])url;
            Context rootctx = new javaRootURLContext(env);
            Object object = null;
            NamingException e = null;
            for (int i = 0; (i < urls.length) && (object == null); i++)
            {
                try
                {
                    object = rootctx.lookup(urls[i]);
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

        if (LOG.isDebugEnabled())
            LOG.debug(">>> No idea what to do, so return a new root context anyway");
        return new javaRootURLContext(env);
    }
}
