//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.jndi.local;

import java.util.Hashtable;
import java.util.Properties;
import javax.naming.Binding;
import javax.naming.CompoundName;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.LinkRef;
import javax.naming.Name;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NotContextException;
import javax.naming.OperationNotSupportedException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.spi.NamingManager;

import org.eclipse.jetty.jndi.NamingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This is a required name for JNDI
// @checkstyle-disable-check : TypeNameCheck

/**
 * localContext
 *
 * Implementation of the delegate for InitialContext for the local namespace.
 */
public class localContextRoot implements Context
{
    private static final Logger LOG = LoggerFactory.getLogger(localContextRoot.class);
    protected static final NamingContext __root = new NamingRoot();
    private final Hashtable<String, Object> _env;

    static class NamingRoot extends NamingContext
    {
        public NamingRoot()
        {
            super(null, null, null, new LocalNameParser());
        }
    }

    static class LocalNameParser implements NameParser
    {
        Properties syntax = new Properties();

        LocalNameParser()
        {
            syntax.put("jndi.syntax.direction", "left_to_right");
            syntax.put("jndi.syntax.separator", "/");
            syntax.put("jndi.syntax.ignorecase", "false");
        }

        @Override
        public Name parse(String name) throws NamingException
        {
            return new CompoundName(name, syntax);
        }
    }

    /*
     * Root has to use the localContextRoot's  env for all operations.
     * So, if createSubcontext in the root, use the env of the localContextRoot.
     * If lookup binding in the root, use the env of the localContextRoot.
     *
     */

    public static NamingContext getRoot()
    {
        return __root;
    }

    public localContextRoot(Hashtable env)
    {
        _env = new Hashtable(env);
    }

    @Override
    public void close() throws NamingException
    {
    }

    @Override
    public String getNameInNamespace() throws NamingException
    {
        return "";
    }

    @Override
    public void destroySubcontext(Name name) throws NamingException
    {
        __root.destroySubcontext(getSuffix(name));
    }

    @Override
    public void destroySubcontext(String name) throws NamingException
    {
        destroySubcontext(__root.getNameParser("").parse(getSuffix(name)));
    }

    @Override
    public Hashtable getEnvironment() throws NamingException
    {
        return _env;
    }

    private Object dereference(Object ctx, String firstComponent) throws NamingException
    {
        if (ctx instanceof Reference)
        {
            //deference the object
            try
            {
                return NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
            }
            catch (NamingException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                LOG.warn("Unable to dereference {}, {}", ctx, firstComponent, e);
                throw new NamingException(e.getMessage());
            }
        }
        return ctx;
    }

    private Context getContext(Name cname) throws NamingException
    {
        String firstComponent = cname.get(0);

        if (firstComponent.equals(""))
            return this;

        Binding binding = __root.getBinding(firstComponent);
        if (binding == null)
        {
            NameNotFoundException nnfe = new NameNotFoundException(firstComponent + " is not bound");
            nnfe.setRemainingName(cname);
            throw nnfe;
        }
        Object ctx = dereference(binding.getObject(), firstComponent);

        if (!(ctx instanceof Context))
            throw new NotContextException(firstComponent + " not a context in " + this.getNameInNamespace());

        return (Context)ctx;
    }

    @Override
    public void unbind(Name name) throws NamingException
    {
        //__root.unbind(getSuffix(name));

        if (name.size() == 0)
            return;

        if (__root.isLocked())
            throw new NamingException("This context is immutable");

        Name cname = __root.toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        //if no subcontexts, just unbind it
        if (cname.size() == 1)
        {
            __root.removeBinding(cname);
        }
        else
        {
            getContext(cname).unbind(cname.getSuffix(1));
        }
    }

    @Override
    public void unbind(String name) throws NamingException
    {
        unbind(__root.getNameParser("").parse(getSuffix(name)));
    }

    @Override
    public Object lookupLink(String name) throws NamingException
    {
        return lookupLink(__root.getNameParser("").parse(getSuffix(name)));
    }

    @Override
    public Object lookupLink(Name name) throws NamingException
    {
        Name cname = __root.toCanonicalName(name);

        if (cname == null || cname.isEmpty())
        {
            //If no name create copy of this context with same bindings, but with copy of the environment so it can be modified
            return __root.shallowCopy(_env);
        }

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        if (cname.size() == 1)
        {
            Binding binding = __root.getBinding(cname);
            if (binding == null)
                throw new NameNotFoundException();

            Object o = binding.getObject();

            //handle links by looking up the link
            if (o instanceof Reference)
            {
                //deference the object
                try
                {
                    return NamingManager.getObjectInstance(o, cname.getPrefix(1), __root, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    LOG.warn("", e);
                    throw new NamingException(e.getMessage());
                }
            }
            else
            {
                //object is either a LinkRef which we don't dereference
                //or a plain object in which case spec says we return it
                return o;
            }
        }

        //it is a multipart name, recurse to the first subcontext
        return getContext(cname).lookup(cname.getSuffix(1));
    }

    @Override
    public Object removeFromEnvironment(String propName) throws NamingException
    {
        return _env.remove(propName);
    }

    @Override
    public Object lookup(Name name) throws NamingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Looking up name=\"{}\\\"", name);
        Name cname = __root.toCanonicalName(name);

        if ((cname == null) || cname.isEmpty())
        {
            return __root.shallowCopy(_env);
        }

        if (cname.size() == 1)
        {
            Binding binding = __root.getBinding(cname);
            if (binding == null)
            {
                NameNotFoundException nnfe = new NameNotFoundException();
                nnfe.setRemainingName(cname);
                throw nnfe;
            }

            Object o = binding.getObject();

            //handle links by looking up the link
            if (o instanceof LinkRef)
            {
                //if link name starts with ./ it is relative to current context
                String linkName = ((LinkRef)o).getLinkName();
                if (linkName.startsWith("./"))
                    return lookup(linkName.substring(2));
                else
                {
                    //link name is absolute
                    InitialContext ictx = new InitialContext();
                    return ictx.lookup(linkName);
                }
            }
            else if (o instanceof Reference)
            {
                // TODO use deference
                try
                {
                    return NamingManager.getObjectInstance(o, cname, __root, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (final Exception e)
                {
                    throw new NamingException(e.getMessage())
                    {
                        {
                            initCause(e);
                        }
                    };
                }
            }
            else
                return o;
        }

        return getContext(cname).lookup(cname.getSuffix(1));
    }

    @Override
    public Object lookup(String name) throws NamingException
    {
        return lookup(__root.getNameParser("").parse(getSuffix(name)));
    }

    @Override
    public void bind(String name, Object obj) throws NamingException
    {
        bind(__root.getNameParser("").parse(getSuffix(name)), obj);
    }

    @Override
    public void bind(Name name, Object obj) throws NamingException
    {
        if (__root.isLocked())
            throw new NamingException("This context is immutable");

        Name cname = __root.toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        //if no subcontexts, just bind it
        if (cname.size() == 1)
        {
            //get the object to be bound
            Object objToBind = NamingManager.getStateToBind(obj, name, this, _env);
            // Check for Referenceable
            if (objToBind instanceof Referenceable)
            {
                objToBind = ((Referenceable)objToBind).getReference();
            }

            //anything else we should be able to bind directly
            __root.addBinding(cname, objToBind);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Checking for existing binding for name={} for first element of name={}", cname, cname.get(0));

            getContext(cname).bind(cname.getSuffix(1), obj);
        }
    }

    @Override
    public void rebind(Name name, Object obj) throws NamingException
    {
        if (__root.isLocked())
            throw new NamingException("This context is immutable");

        Name cname = __root.toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        //if no subcontexts, just bind it
        if (cname.size() == 1)
        {
            //check if it is a Referenceable
            Object objToBind = NamingManager.getStateToBind(obj, name, __root, _env);

            if (objToBind instanceof Referenceable)
            {
                objToBind = ((Referenceable)objToBind).getReference();
            }
            __root.removeBinding(cname);
            __root.addBinding(cname, objToBind);
        }
        else
        {
            //walk down the subcontext hierarchy
            if (LOG.isDebugEnabled())
                LOG.debug("Checking for existing binding for name={} for first element of name={}", cname, cname.get(0));

            getContext(cname).rebind(cname.getSuffix(1), obj);
        }
    }

    @Override
    public void rebind(String name, Object obj) throws NamingException
    {
        rebind(__root.getNameParser("").parse(getSuffix(name)), obj);
    }

    @Override
    public void rename(Name oldName, Name newName) throws NamingException
    {
        throw new OperationNotSupportedException();
    }

    @Override
    public void rename(String oldName, String newName) throws NamingException
    {
        throw new OperationNotSupportedException();
    }

    @Override
    public Context createSubcontext(String name) throws NamingException
    {
        //if the subcontext comes directly off the root, use the env of the InitialContext
        //as the root itself has no environment. Otherwise, it inherits the env of the parent
        //Context further down the tree.
        //NamingContext ctx = (NamingContext)__root.createSubcontext(name);
        //if (ctx.getParent() == __root)
        //    ctx.setEnv(_env);
        //return ctx;

        return createSubcontext(__root.getNameParser("").parse(name));
    }

    @Override
    public Context createSubcontext(Name name) throws NamingException
    {
        //if the subcontext comes directly off the root, use the env of the InitialContext
        //as the root itself has no environment. Otherwise, it inherits the env of the parent
        //Context further down the tree.
        //NamingContext ctx = (NamingContext)__root.createSubcontext(getSuffix(name));
        //if (ctx.getParent() == __root)
        //    ctx.setEnv(_env);
        //return ctx;

        if (__root.isLocked())
        {
            NamingException ne = new NamingException("This context is immutable");
            ne.setRemainingName(name);
            throw ne;
        }

        Name cname = __root.toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");
        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        if (cname.size() == 1)
        {
            //not permitted to bind if something already bound at that name
            Binding binding = __root.getBinding(cname);
            if (binding != null)
                throw new NameAlreadyBoundException(cname.toString());

            //make a new naming context with the root as the parent
            Context ctx = new NamingContext(_env, cname.get(0), __root, __root.getNameParser(""));
            __root.addBinding(cname, ctx);
            return ctx;
        }

        //If the name has multiple subcontexts,
        return getContext(cname).createSubcontext(cname.getSuffix(1));
    }

    @Override
    public NameParser getNameParser(String name) throws NamingException
    {
        return __root.getNameParser(name);
    }

    @Override
    public NameParser getNameParser(Name name) throws NamingException
    {
        return __root.getNameParser(name);
    }

    @Override
    public NamingEnumeration list(String name) throws NamingException
    {
        return __root.list(name);
    }

    @Override
    public NamingEnumeration list(Name name) throws NamingException
    {
        return __root.list(name);
    }

    @Override
    public NamingEnumeration listBindings(Name name) throws NamingException
    {
        return __root.listBindings(name);
    }

    @Override
    public NamingEnumeration listBindings(String name) throws NamingException
    {
        return __root.listBindings(name);
    }

    @Override
    public Object addToEnvironment(String propName, Object propVal)
        throws NamingException
    {
        return _env.put(propName, propVal);
    }

    @Override
    public String composeName(String name, String prefix)
        throws NamingException
    {
        return __root.composeName(name, prefix);
    }

    @Override
    public Name composeName(Name name, Name prefix) throws NamingException
    {
        return __root.composeName(name, prefix);
    }

    protected String getSuffix(String url) throws NamingException
    {
        return url;
    }

    protected Name getSuffix(Name name) throws NamingException
    {
        return name;
    }
}
