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

package org.eclipse.jetty.jndi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * NamingContext
 * <p>
 * Implementation of Context interface.
 * <p>
 * <b>Notes:</b>
 * All Names are expected to be Compound, not Composite.
 */
@SuppressWarnings("unchecked")
public class NamingContext implements Context, Dumpable
{
    private static final Logger LOG = Log.getLogger(NamingContext.class);
    private static final List<Binding> __empty = Collections.emptyList();
    public static final String DEEP_BINDING = "org.eclipse.jetty.jndi.deepBinding";
    public static final String LOCK_PROPERTY = "org.eclipse.jetty.jndi.lock";
    public static final String UNLOCK_PROPERTY = "org.eclipse.jetty.jndi.unlock";

    /*
     * The env is stored as a Hashtable to be compatible with the API.
     * There is no need for concurrent protection (see Concurrent Access section
     * of the {@link Context} javadoc), so multiple threads acting on the same
     * Context env need to self - mutually exclude.
     */
    protected final Hashtable<String, Object> _env = new Hashtable<>();

    /*
     * This contexts bindings are stored as a ConcurrentHashMap.
     * There is generally no need for concurrent protection (see Concurrent Access section
     * of the {@link Context} javadoc), However, the NamingContext is used for root contexts and
     * we do not mutually exclude when initializing, so instead we do make the bindings
     * thread safe (unlike the env where we expect users to respect the Concurrent Access requirements).
     */
    protected final ConcurrentMap<String, Binding> _bindings;

    protected NamingContext _parent = null;
    protected String _name = null;
    protected NameParser _parser = null;
    private Collection<Listener> _listeners;
    private Object _lock;

    /**
     * Naming Context Listener.
     */
    public interface Listener
    {
        /**
         * Called by {@link NamingContext#addBinding(Name, Object)} when adding
         * a binding.
         *
         * @param ctx The context to add to.
         * @param binding The binding to add.
         * @return The binding to bind, or null if the binding should be ignored.
         */
        Binding bind(NamingContext ctx, Binding binding);

        /**
         * @param ctx The context to unbind from
         * @param binding The binding that was unbound.
         */
        void unbind(NamingContext ctx, Binding binding);
    }

    /**
     * Constructor
     *
     * @param env environment properties which are copied into this Context's environment
     * @param name relative name of this context
     * @param parent immediate ancestor Context (can be null)
     * @param parser NameParser for this Context
     */
    public NamingContext(Hashtable<String, Object> env,
                         String name,
                         NamingContext parent,
                         NameParser parser)
    {
        this(env, name, parent, parser, null);
    }

    protected NamingContext(Hashtable<String, Object> env,
                            String name,
                            NamingContext parent,
                            NameParser parser,
                            ConcurrentMap<String, Binding> bindings)
    {
        if (env != null)
            _env.putAll(env);
        _name = name;
        _parent = parent;
        _parser = parser;
        _bindings = bindings == null ? new ConcurrentHashMap<>() : bindings;
        if (LOG.isDebugEnabled())
            LOG.debug("new {}", this);
    }

    /**
     * @return A shallow copy of the Context with the same bindings, but with the passed environment
     */
    public Context shallowCopy(Hashtable<String, Object> env)
    {
        return new NamingContext(env, _name, _parent, _parser, _bindings);
    }

    public boolean isDeepBindingSupported()
    {
        // look for deep binding support in _env
        Object support = _env.get(DEEP_BINDING);
        if (support != null)
            return Boolean.parseBoolean(String.valueOf(support));

        if (_parent != null)
            return _parent.isDeepBindingSupported();
        // probably a root context
        return Boolean.parseBoolean(System.getProperty(DEEP_BINDING, "false"));
    }

    /**
     * Getter for _name
     *
     * @return name of this Context (relative, not absolute)
     */
    public String getName()
    {
        return _name;
    }

    /**
     * Getter for _parent
     *
     * @return parent Context
     */
    public Context getParent()
    {
        return _parent;
    }

    public void setNameParser(NameParser parser)
    {
        _parser = parser;
    }

    public final void setEnv(Hashtable<String, Object> env)
    {
        Object lock = _env.get(LOCK_PROPERTY);
        try
        {
            _env.clear();
            if (env == null)
                return;
            _env.putAll(env);
        }
        finally
        {
            if (lock != null)
                _env.put(LOCK_PROPERTY, lock);
        }
    }

    private Object dereference(Object ctx, String firstComponent) throws NamingException
    {
        if (ctx instanceof Reference)
        {
            //deference the object
            try
            {
                return NamingManager.getObjectInstance(ctx, _parser.parse(firstComponent), this, _env);
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
        return ctx;
    }

    private Context getContext(Name cname) throws NamingException
    {
        String firstComponent = cname.get(0);

        if (firstComponent.equals(""))
            return this;

        Binding binding = getBinding(firstComponent);
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

    /**
     * Bind a name to an object
     *
     * @param name Name of the object
     * @param obj object to bind
     * @throws NamingException if an error occurs
     */
    @Override
    public void bind(Name name, Object obj)
        throws NamingException
    {
        if (isLocked())
            throw new NamingException("This context is immutable");

        Name cname = toCanonicalName(name);

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
            addBinding(cname, objToBind);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Checking for existing binding for name=" + cname + " for first element of name=" + cname.get(0));

            //walk down the subcontext hierarchy
            //need to ignore trailing empty "" name components

            String firstComponent = cname.get(0);
            Object ctx = null;

            if (firstComponent.equals(""))
                ctx = this;
            else
            {
                Binding binding = getBinding(firstComponent);
                if (binding == null)
                {
                    if (isDeepBindingSupported())
                    {
                        Name subname = _parser.parse(firstComponent);
                        Context subctx = new NamingContext(_env, firstComponent, this, _parser, null);
                        addBinding(subname, subctx);
                        binding = getBinding(subname);
                    }
                    else
                    {
                        throw new NameNotFoundException(firstComponent + " is not bound");
                    }
                }

                ctx = dereference(binding.getObject(), firstComponent);
            }

            if (ctx instanceof Context)
            {
                ((Context)ctx).bind(cname.getSuffix(1), obj);
            }
            else
                throw new NotContextException("Object bound at " + firstComponent + " is not a Context");
        }
    }

    /**
     * Bind a name (as a String) to an object
     *
     * @param name a <code>String</code> value
     * @param obj an <code>Object</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void bind(String name, Object obj)
        throws NamingException
    {
        bind(_parser.parse(name), obj);
    }

    /**
     * Create a context as a child of this one
     *
     * @param name a <code>Name</code> value
     * @return a <code>Context</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public Context createSubcontext(Name name)
        throws NamingException
    {
        if (isLocked())
        {
            NamingException ne = new NamingException("This context is immutable");
            ne.setRemainingName(name);
            throw ne;
        }

        Name cname = toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");
        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        if (cname.size() == 1)
        {
            //not permitted to bind if something already bound at that name
            Binding binding = getBinding(cname);
            if (binding != null)
                throw new NameAlreadyBoundException(cname.toString());

            Context ctx = new NamingContext(_env, cname.get(0), this, _parser);
            addBinding(cname, ctx);
            return ctx;
        }

        return getContext(cname).createSubcontext(cname.getSuffix(1));
    }

    /**
     * Create a Context as a child of this one
     *
     * @param name a <code>String</code> value
     * @return a <code>Context</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public Context createSubcontext(String name)
        throws NamingException
    {
        return createSubcontext(_parser.parse(name));
    }

    /**
     * @param name name of subcontext to remove
     * @throws NamingException if an error occurs
     */
    @Override
    public void destroySubcontext(String name)
        throws NamingException
    {
        removeBinding(_parser.parse(name));
    }

    /**
     * @param name name of subcontext to remove
     * @throws NamingException if an error occurs
     */
    @Override
    public void destroySubcontext(Name name)
        throws NamingException
    {
        removeBinding(name);
    }

    /**
     * Lookup a binding by name
     *
     * @param name name of bound object
     * @throws NamingException if an error occurs
     */
    @Override
    public Object lookup(Name name)
        throws NamingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Looking up name=\"" + name + "\"");
        Name cname = toCanonicalName(name);

        if ((cname == null) || (cname.size() == 0))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Null or empty name, returning shallowCopy of this context");
            return shallowCopy(_env);
        }

        if (cname.size() == 1)
        {
            Binding binding = getBinding(cname);
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
                    return this.lookup(linkName.substring(2));
                else
                {
                    //link name is absolute
                    InitialContext ictx = new InitialContext();
                    return ictx.lookup(linkName);
                }
            }
            else if (o instanceof Reference)
            {
                // TODO use deference ??
                try
                {
                    return NamingManager.getObjectInstance(o, cname, this, _env);
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
                return o;
        }

        return getContext(cname).lookup(cname.getSuffix(1));
    }

    /**
     * Lookup binding of an object by name
     *
     * @param name name of bound object
     * @return object bound to name
     * @throws NamingException if an error occurs
     */
    @Override
    public Object lookup(String name)
        throws NamingException
    {
        return lookup(_parser.parse(name));
    }

    /**
     * Lookup link bound to name
     *
     * @param name name of link binding
     * @return LinkRef or plain object bound at name
     * @throws NamingException if an error occurs
     */
    @Override
    public Object lookupLink(Name name)
        throws NamingException
    {
        Name cname = toCanonicalName(name);

        if (cname == null || name.isEmpty())
        {
            return shallowCopy(_env);
        }

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        if (cname.size() == 1)
        {
            Binding binding = getBinding(cname);
            if (binding == null)
                throw new NameNotFoundException();

            return dereference(binding.getObject(), cname.getPrefix(1).toString());
        }

        return getContext(cname).lookup(cname.getSuffix(1));
    }

    /**
     * Lookup link bound to name
     *
     * @param name name of link binding
     * @return LinkRef or plain object bound at name
     * @throws NamingException if an error occurs
     */
    @Override
    public Object lookupLink(String name)
        throws NamingException
    {
        return lookupLink(_parser.parse(name));
    }

    /**
     * List all names bound at Context named by Name
     *
     * @param name a <code>Name</code> value
     * @return a <code>NamingEnumeration</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public NamingEnumeration list(Name name)
        throws NamingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("list() on Context=" + getName() + " for name=" + name);
        Name cname = toCanonicalName(name);

        if (cname == null)
        {
            return new NameEnumeration(__empty.iterator());
        }

        if (cname.size() == 0)
        {
            return new NameEnumeration(_bindings.values().iterator());
        }

        //multipart name
        return getContext(cname).list(cname.getSuffix(1));
    }

    /**
     * List all names bound at Context named by Name
     *
     * @param name a <code>Name</code> value
     * @return a <code>NamingEnumeration</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public NamingEnumeration list(String name)
        throws NamingException
    {
        return list(_parser.parse(name));
    }

    /**
     * List all Bindings present at Context named by Name
     *
     * @param name a <code>Name</code> value
     * @return a <code>NamingEnumeration</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public NamingEnumeration listBindings(Name name)
        throws NamingException
    {
        Name cname = toCanonicalName(name);

        if (cname == null)
        {
            return new BindingEnumeration(__empty.iterator());
        }

        if (cname.size() == 0)
        {
            return new BindingEnumeration(_bindings.values().iterator());
        }

        return getContext(cname).listBindings(cname.getSuffix(1));
    }

    /**
     * List all Bindings at Name
     *
     * @param name a <code>String</code> value
     * @return a <code>NamingEnumeration</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public NamingEnumeration listBindings(String name)
        throws NamingException
    {
        return listBindings(_parser.parse(name));
    }

    /**
     * Overwrite or create a binding
     *
     * @param name a <code>Name</code> value
     * @param obj an <code>Object</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void rebind(Name name,
                       Object obj)
        throws NamingException
    {
        if (isLocked())
            throw new NamingException("This context is immutable");

        Name cname = toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        //if no subcontexts, just bind it
        if (cname.size() == 1)
        {
            //check if it is a Referenceable
            Object objToBind = NamingManager.getStateToBind(obj, name, this, _env);

            if (objToBind instanceof Referenceable)
            {
                objToBind = ((Referenceable)objToBind).getReference();
            }
            removeBinding(cname);
            addBinding(cname, objToBind);
        }
        else
        {
            getContext(cname).rebind(cname.getSuffix(1), obj);
        }
    }

    /**
     * Overwrite or create a binding from Name to Object
     *
     * @param name a <code>String</code> value
     * @param obj an <code>Object</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void rebind(String name,
                       Object obj)
        throws NamingException
    {
        rebind(_parser.parse(name), obj);
    }

    /**
     * Not supported.
     *
     * @param name a <code>String</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void unbind(String name)
        throws NamingException
    {
        unbind(_parser.parse(name));
    }

    /**
     * Not supported.
     *
     * @param name a <code>String</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void unbind(Name name)
        throws NamingException
    {
        if (name.size() == 0)
            return;

        if (isLocked())
            throw new NamingException("This context is immutable");

        Name cname = toCanonicalName(name);

        if (cname == null)
            throw new NamingException("Name is null");

        if (cname.size() == 0)
            throw new NamingException("Name is empty");

        //if no subcontexts, just unbind it
        if (cname.size() == 1)
        {
            removeBinding(cname);
        }
        else
        {
            getContext(cname).unbind(cname.getSuffix(1));
        }
    }

    /**
     * Not supported
     *
     * @param oldName a <code>Name</code> value
     * @param newName a <code>Name</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void rename(Name oldName,
                       Name newName)
        throws NamingException
    {
        throw new OperationNotSupportedException();
    }

    /**
     * Not supported
     *
     * @param oldName a <code>Name</code> value
     * @param newName a <code>Name</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public void rename(String oldName,
                       String newName)
        throws NamingException
    {
        throw new OperationNotSupportedException();
    }

    /**
     * Join two names together. These are treated as
     * CompoundNames.
     *
     * @param name a <code>Name</code> value
     * @param prefix a <code>Name</code> value
     * @return a <code>Name</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public Name composeName(Name name,
                            Name prefix)
        throws NamingException
    {
        if (name == null)
            throw new NamingException("Name cannot be null");
        if (prefix == null)
            throw new NamingException("Prefix cannot be null");

        Name compoundName = (CompoundName)prefix.clone();
        compoundName.addAll(name);
        return compoundName;
    }

    /**
     * Join two names together. These are treated as
     * CompoundNames.
     *
     * @param name a <code>Name</code> value
     * @param prefix a <code>Name</code> value
     * @return a <code>Name</code> value
     * @throws NamingException if an error occurs
     */
    @Override
    public String composeName(String name,
                              String prefix)
        throws NamingException
    {
        if (name == null)
            throw new NamingException("Name cannot be null");
        if (prefix == null)
            throw new NamingException("Prefix cannot be null");

        Name compoundName = _parser.parse(prefix);
        compoundName.add(name);
        return compoundName.toString();
    }

    /**
     * Do nothing
     *
     * @throws NamingException if an error occurs
     */
    @Override
    public void close()
        throws NamingException
    {
    }

    /**
     * Return a NameParser for this Context.
     *
     * @param name a <code>Name</code> value
     * @return a <code>NameParser</code> value
     */
    @Override
    public NameParser getNameParser(Name name)
    {
        return _parser;
    }

    /**
     * Return a NameParser for this Context.
     *
     * @param name a <code>Name</code> value
     * @return a <code>NameParser</code> value
     */
    @Override
    public NameParser getNameParser(String name)
    {
        return _parser;
    }

    /**
     * Get the full name of this Context node
     * by visiting it's ancestors back to root.
     *
     * NOTE: if this Context has a URL namespace then
     * the URL prefix will be missing
     *
     * @return the full name of this Context
     * @throws NamingException if an error occurs
     */
    @Override
    public String getNameInNamespace()
        throws NamingException
    {
        Name name = _parser.parse("");

        NamingContext c = this;
        while (c != null)
        {
            String str = c.getName();
            if (str != null)
                name.add(0, str);
            c = (NamingContext)c.getParent();
        }
        return name.toString();
    }

    /**
     * Add an environment setting to this Context
     *
     * @param propName name of the property to add
     * @param propVal value of the property to add
     * @return propVal or previous value of the property
     * @throws NamingException if an error occurs
     */
    @Override
    public Object addToEnvironment(String propName,
                                   Object propVal)
        throws NamingException
    {
        switch (propName)
        {
            case LOCK_PROPERTY:
                if (_lock == null)
                    _lock = propVal;
                return null;

            case UNLOCK_PROPERTY:
                if (propVal != null && propVal.equals(_lock))
                    _lock = null;
                return null;

            default:
                if (isLocked())
                    throw new NamingException("This context is immutable");
                return _env.put(propName, propVal);
        }
    }

    /**
     * Remove a property from this Context's environment.
     *
     * @param propName name of property to remove
     * @return value of property or null if it didn't exist
     * @throws NamingException if an error occurs
     */
    @Override
    public Object removeFromEnvironment(String propName)
        throws NamingException
    {
        if (isLocked())
            throw new NamingException("This context is immutable");

        return _env.remove(propName);
    }

    /**
     * Get the environment of this Context.
     *
     * @return a copy of the environment of this Context.
     */
    @Override
    public Hashtable getEnvironment()
    {
        return _env;
    }

    /**
     * Add a name to object binding to this Context.
     *
     * @param name a <code>Name</code> value
     * @param obj an <code>Object</code> value
     * @throws NameAlreadyBoundException if name already bound
     */
    public void addBinding(Name name, Object obj) throws NameAlreadyBoundException
    {
        String key = name.toString();
        Binding binding = new Binding(key, obj);

        Collection<Listener> list = findListeners();

        for (Listener listener : list)
        {
            binding = listener.bind(this, binding);
            if (binding == null)
                break;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Adding binding with key=" + key + " obj=" + obj + " for context=" + _name + " as " + binding);

        if (binding != null)
        {
            if (_bindings.putIfAbsent(key, binding) != null)
            {
                if (isDeepBindingSupported())
                {
                    // quietly return (no exception)
                    // this is jndi spec breaking, but is added to support broken
                    // jndi users like openejb.
                    return;
                }
                throw new NameAlreadyBoundException(name.toString());
            }
        }
    }

    /**
     * Get a name to object binding from this Context
     *
     * @param name a <code>Name</code> value
     * @return a <code>Binding</code> value
     */
    public Binding getBinding(Name name)
    {
        return _bindings.get(name.toString());
    }

    /**
     * Get a name to object binding from this Context
     *
     * @param name as a String
     * @return null or the Binding
     */
    public Binding getBinding(String name)
    {
        return _bindings.get(name);
    }

    public void removeBinding(Name name)
    {
        String key = name.toString();
        if (LOG.isDebugEnabled())
            LOG.debug("Removing binding with key=" + key);
        Binding binding = _bindings.remove(key);
        if (binding != null)
        {
            Collection<Listener> list = findListeners();
            for (Listener listener : list)
            {
                listener.unbind(this, binding);
            }
        }
    }

    /**
     * Remove leading or trailing empty components from
     * name. Eg "/comp/env/" -&gt; "comp/env"
     *
     * @param name the name to normalize
     * @return normalized name
     */
    public Name toCanonicalName(Name name)
    {
        Name canonicalName = name;

        if (name != null)
        {
            if (canonicalName.size() > 1)
            {
                if (canonicalName.get(0).equals(""))
                    canonicalName = canonicalName.getSuffix(1);

                if (canonicalName.get(canonicalName.size() - 1).equals(""))
                    canonicalName = canonicalName.getPrefix(canonicalName.size() - 1);
            }
        }

        return canonicalName;
    }

    public boolean isLocked()
    {
        //TODO lock whole hierarchy?
        return _lock != null;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Map<String, Object> bindings = new HashMap<>();
        for (Map.Entry<String, Binding> binding : _bindings.entrySet())
        {
            bindings.put(binding.getKey(), binding.getValue().getObject());
        }

        Dumpable.dumpObject(out, this);
        Dumpable.dumpMapEntries(out, indent, bindings, _env.isEmpty());
        if (!_env.isEmpty())
        {
            out.append(indent).append("+> environment\n");
            Dumpable.dumpMapEntries(out, indent + "   ", _env, true);
        }
    }

    private Collection<Listener> findListeners()
    {
        Collection<Listener> list = new ArrayList<Listener>();
        NamingContext ctx = this;
        while (ctx != null)
        {
            if (ctx._listeners != null)
                list.addAll(ctx._listeners);
            ctx = (NamingContext)ctx.getParent();
        }
        return list;
    }

    public void addListener(Listener listener)
    {
        if (_listeners == null)
            _listeners = new ArrayList<Listener>();
        _listeners.add(listener);
    }

    public boolean removeListener(Listener listener)
    {
        return _listeners.remove(listener);
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(this.getClass().getName()).append('@').append(Integer.toHexString(this.hashCode()));
        buf.append("[name=").append(this._name);
        buf.append(",parent=");
        if (this._parent != null)
        {
            buf.append(this._parent.getClass().getName()).append('@').append(Integer.toHexString(this._parent.hashCode()));
        }
        buf.append(",bindings");
        if (this._bindings == null)
        {
            buf.append("=<null>");
        }
        else
        {
            buf.append(".size=").append(this._bindings.size());
        }
        buf.append(']');
        return buf.toString();
    }
}
