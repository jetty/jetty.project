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

package org.eclipse.jetty.jndi;

import java.util.HashMap;
import java.util.Map;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

import org.eclipse.jetty.util.log.Logger;


/**
 * Naming Utility Methods
 */
public class NamingUtil
{
    public final static Logger __log=org.eclipse.jetty.util.log.Log.getLogger("jndi");

    /* ------------------------------------------------------------ */
    /**
     * Bind an object to a context ensuring all sub-contexts
     * are created if necessary
     *
     * @param ctx the context into which to bind
     * @param nameStr the name relative to context to bind
     * @param obj the object to be bound
     * @return the bound context
     * @exception NamingException if an error occurs
     */
    public static Context bind (Context ctx, String nameStr, Object obj)
        throws NamingException
    {
        Name name = ctx.getNameParser("").parse(nameStr);

        //no name, nothing to do
        if (name.size() == 0)
            return null;

        Context subCtx = ctx;

        //last component of the name will be the name to bind
        for (int i=0; i < name.size() - 1; i++)
        {
            try
            {
                subCtx = (Context)subCtx.lookup (name.get(i));
                if(__log.isDebugEnabled())
                    __log.debug("Subcontext "+name.get(i)+" already exists");
            }
            catch (NameNotFoundException e)
            {
                subCtx = subCtx.createSubcontext(name.get(i));
                if(__log.isDebugEnabled())
                    __log.debug("Subcontext "+name.get(i)+" created");
            }
        }

        subCtx.rebind (name.get(name.size() - 1), obj);
        if(__log.isDebugEnabled())
            __log.debug("Bound object to "+name.get(name.size() - 1));
        return subCtx;
    }

    public static void unbind (Context ctx)
    throws NamingException
    {
        //unbind everything in the context and all of its subdirectories
        NamingEnumeration ne = ctx.listBindings(ctx.getNameInNamespace());

        while (ne.hasMoreElements())
        {
            Binding b = (Binding)ne.nextElement();
            if (b.getObject() instanceof Context)
            {
                unbind((Context)b.getObject());
            }
            else
                ctx.unbind(b.getName());
        }
    }

    /**
     * Do a deep listing of the bindings for a context.
     * @param ctx the context containing the name for which to list the bindings
     * @param name the name in the context to list
     * @return map: key is fully qualified name, value is the bound object
     * @throws NamingException if unable to flatten bindings
     */
    public static Map flattenBindings (Context ctx, String name)
    throws NamingException
    {
        HashMap map = new HashMap();

        //the context representation of name arg
        Context c = (Context)ctx.lookup (name);
        NameParser parser = c.getNameParser("");
        NamingEnumeration enm = ctx.listBindings(name);
        while (enm.hasMore())
        {
            Binding b = (Binding)enm.next();

            if (b.getObject() instanceof Context)
            {
                map.putAll(flattenBindings (c, b.getName()));
            }
            else
            {
                Name compoundName = parser.parse (c.getNameInNamespace());
                compoundName.add(b.getName());
                map.put (compoundName.toString(), b.getObject());
            }

        }

        return map;
    }

}
