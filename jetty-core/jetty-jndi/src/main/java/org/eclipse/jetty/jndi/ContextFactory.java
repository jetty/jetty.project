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

package org.eclipse.jetty.jndi;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;
import java.util.WeakHashMap;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContextFactory
 * <p>
 * This is an object factory that produces a jndi naming
 * context based on a classloader.
 * <p>
 * It is used for the <code>java:comp</code> context.
 * <p>
 * This object factory is bound at <code>java:comp</code>. When a
 * lookup arrives for java:comp,  this object factory
 * is invoked and will return a context specific to
 * the caller's environment (so producing the <code>java:comp/env</code>
 * specific to a webapp).
 * <p>
 * The context selected is based on classloaders. First
 * we try looking at the thread context classloader if it is set, and walk its
 * hierarchy, creating a context if none is found. If the thread context classloader
 * is not set, then we use the classloader associated with the current Context.
 * <p>
 * If there is no current context, or no classloader, we return null.
 */
public class ContextFactory implements ObjectFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextFactory.class);

    /**
     * Map of classloaders to contexts.
     */
    private static final Map<ClassLoader, Context> __contextMap = new WeakHashMap<>();

    /**
     * Threadlocal for injecting a context to use
     * instead of looking up the map.
     */
    private static final ThreadLocal<Context> __threadContext = new ThreadLocal<Context>();

    /**
     * Threadlocal for setting a classloader which must be used
     * when finding the comp context.
     */
    private static final ThreadLocal<ClassLoader> __threadClassLoader = new ThreadLocal<ClassLoader>();

    private static final AutoLock __lock = new AutoLock();

    /**
     * Find or create a context which pertains to a classloader.
     * <p>
     * If the thread context classloader is set, we try to find an already-created naming context
     * for it. If one does not exist, we walk its classloader hierarchy until one is found, or we
     * run out of parent classloaders. In the latter case, we will create a new naming context associated
     * with the original thread context classloader.
     * <p>
     * If the thread context classloader is not set, we obtain the classloader from the current
     * jetty Context, and look for an already-created naming context.
     * <p>
     * If there is no current jetty Context, or it has no associated classloader, we
     * return null.
     *
     * @see javax.naming.spi.ObjectFactory#getObjectInstance(java.lang.Object, javax.naming.Name, javax.naming.Context, java.util.Hashtable)
     */
    @Override
    public Object getObjectInstance(Object obj,
                                    Name name,
                                    Context nameCtx,
                                    Hashtable env)
        throws Exception
    {
        //First, see if we have had a context injected into us to use.
        Context ctx = (Context)__threadContext.get();
        if (ctx != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Using the Context that is bound on the thread");
            return ctx;
        }

        //See if there is a classloader to use for finding the comp context
        //Don't use its parent hierarchy if set.
        ClassLoader loader = (ClassLoader)__threadClassLoader.get();
        if (loader != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Using threadlocal classloader");
            try (AutoLock l = __lock.lock())
            {
                ctx = getContextForClassLoader(loader);
                if (ctx == null)
                {
                    ctx = newNamingContext(obj, loader, env, name, nameCtx);
                    __contextMap.put(loader, ctx);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Made context {} for classloader {}", name.get(0), loader);
                }
                return ctx;
            }
        }

        //If the thread context classloader is set, then try its hierarchy to find a matching context
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        loader = tccl;
        if (loader != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Trying thread context classloader");
            try (AutoLock l = __lock.lock())
            {
                while (ctx == null && loader != null)
                {
                    ctx = getContextForClassLoader(loader);
                    if (ctx == null && loader != null)
                        loader = loader.getParent();
                }

                if (ctx == null)
                {
                    ctx = newNamingContext(obj, tccl, env, name, nameCtx);
                    __contextMap.put(tccl, ctx);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Made context {} for classloader {}", name.get(0), tccl);
                }
                return ctx;
            }
        }

        //If trying thread context classloader hierarchy failed, try the
        //classloader associated with the current context
        if (ContextHandler.getCurrentContext() != null)
        {
            if (LOG.isDebugEnabled() && loader != null)
                LOG.debug("Trying classloader of current org.eclipse.jetty.server.handler.ContextHandler");
            try (AutoLock l = __lock.lock())
            {
                loader = ContextHandler.getCurrentContext().getClassLoader();
                ctx = (Context)__contextMap.get(loader);

                if (ctx == null && loader != null)
                {
                    ctx = newNamingContext(obj, loader, env, name, nameCtx);
                    __contextMap.put(loader, ctx);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Made context {} for classloader {} ", name.get(0), loader);
                }

                return ctx;
            }
        }
        return null;
    }

    /**
     * Create a new NamingContext.
     *
     * @param obj the object to create
     * @param loader the classloader for the naming context
     * @param env the jndi env for the entry
     * @param name the name of the entry
     * @param parentCtx the parent context of the entry
     * @return the newly created naming context
     * @throws Exception if unable to create a new naming context
     */
    public NamingContext newNamingContext(Object obj, ClassLoader loader, Hashtable env, Name name, Context parentCtx)
        throws Exception
    {
        Reference ref = (Reference)obj;
        StringRefAddr parserAddr = (StringRefAddr)ref.get("parser");
        String parserClassName = (parserAddr == null ? null : (String)parserAddr.getContent());
        NameParser parser =
            (NameParser)(parserClassName == null
                ? null
                : loader.loadClass(parserClassName).getDeclaredConstructor().newInstance());

        return new NamingContext(env,
            name.get(0),
            (NamingContext)parentCtx,
            parser);
    }

    /**
     * Find the naming Context for the given classloader
     *
     * @param loader the classloader for the context
     * @return the context for the classloader
     */
    public Context getContextForClassLoader(ClassLoader loader)
    {
        if (loader == null)
            return null;
        try (AutoLock l = __lock.lock())
        {
            return __contextMap.get(loader);
        }
    }

    /**
     * Associate the given Context with the current thread.
     * disassociate method should be called to reset the context.
     *
     * @param ctx the context to associate to the current thread.
     * @return the previous context associated on the thread (can be null)
     */
    public static Context associateContext(final Context ctx)
    {
        Context previous = (Context)__threadContext.get();
        __threadContext.set(ctx);
        return previous;
    }

    public static void disassociateContext(final Context ctx)
    {
        __threadContext.set(null);
    }

    public static ClassLoader associateClassLoader(final ClassLoader loader)
    {
        ClassLoader prev = (ClassLoader)__threadClassLoader.get();
        __threadClassLoader.set(loader);
        return prev;
    }

    public static void disassociateClassLoader()
    {
        __threadClassLoader.set(null);
    }

    public static void dump(Appendable out, String indent) throws IOException
    {
        try (AutoLock l = __lock.lock())
        {
            Dumpable.dumpObjects(out, indent, String.format("o.e.j.jndi.ContextFactory@%x", __contextMap.hashCode()), __contextMap);
        }
    }
}
