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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

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
public class NamingContext implements Context, Cloneable, Dumpable
{
    private final static Logger __log=NamingUtil.__log;
    private final static List<Binding> __empty = Collections.emptyList();
    public static final String DEEP_BINDING = "org.eclipse.jetty.jndi.deepBinding";
    public static final String LOCK_PROPERTY = "org.eclipse.jetty.jndi.lock";
    public static final String UNLOCK_PROPERTY = "org.eclipse.jetty.jndi.unlock";

    protected final Hashtable<String,Object> _env = new Hashtable<String,Object>();
    private boolean _supportDeepBinding = false;
    protected Map<String,Binding> _bindings = new HashMap<String,Binding>();

    protected NamingContext _parent = null;
    protected String _name = null;
    protected NameParser _parser = null;
    private Collection<Listener> _listeners;

    /*------------------------------------------------*/
    /**
     * Naming Context Listener.
     */
    public interface Listener
    {
        /**
         * Called by {@link NamingContext#addBinding(Name, Object)} when adding
         * a binding.
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

    /*------------------------------------------------*/
    /**
     * Constructor
     *
     * @param env environment properties
     * @param name relative name of this context
     * @param parent immediate ancestor Context (can be null)
     * @param parser NameParser for this Context
     */
    public NamingContext(Hashtable<String,Object> env,
                         String name,
                         NamingContext parent,
                         NameParser parser)
    {
        if (env != null)
        {
            _env.putAll(env);
            // look for deep binding support in _env
            Object support = _env.get(DEEP_BINDING);
            if (support == null)
                _supportDeepBinding = false;
            else
                _supportDeepBinding = support != null?Boolean.parseBoolean(support.toString()):false;
        }
        else
        {
            // no env?  likely this is a root context (java or local) that
            // was created without an _env.  Look for a system property.
            String value = System.getProperty(DEEP_BINDING,"false");
            _supportDeepBinding = Boolean.parseBoolean(value);
            // put what we discovered into the _env for later sub-contexts
            // to utilize.
            _env.put(DEEP_BINDING,_supportDeepBinding);
        }
        _name = name;
        _parent = parent;
        _parser = parser;
        if(__log.isDebugEnabled())
            __log.debug("supportDeepBinding={}",_supportDeepBinding);
    }


    /*------------------------------------------------*/
    /**
     * Clone this NamingContext
     *
     * @return copy of this NamingContext
     * @exception CloneNotSupportedException if an error occurs
     */
    public Object clone ()
        throws CloneNotSupportedException
    {
        NamingContext ctx = (NamingContext)super.clone();
        ctx._env.putAll(_env);
        ctx._bindings.putAll(_bindings);
        return ctx;
    }


    /*------------------------------------------------*/
    /**
     * Getter for _name
     *
     * @return name of this Context (relative, not absolute)
     */
    public String getName ()
    {
        return _name;
    }

    /*------------------------------------------------*/
    /**
     * Getter for _parent
     *
     * @return parent Context
     */
    public Context getParent()
    {
        return _parent;
    }

    /*------------------------------------------------*/
    public void setNameParser (NameParser parser)
    {
        _parser = parser;
    }


    public final void setEnv (Hashtable<String,Object> env)
    {
        _env.clear();
        if(env == null)
            return;
        _env.putAll(env);
        _supportDeepBinding = _env.containsKey(DEEP_BINDING);
    }


    public Map<String,Binding> getBindings ()
    {
        return _bindings;
    }

    public void setBindings(Map<String,Binding> bindings)
    {
        _bindings = bindings;
    }

    /*------------------------------------------------*/
    /**
     * Bind a name to an object
     *
     * @param name Name of the object
     * @param obj object to bind
     * @exception NamingException if an error occurs
     */
    public void bind(Name name, Object obj)
        throws NamingException
    {
        if (isLocked())
            throw new NamingException ("This context is immutable");

        Name cname = toCanonicalName(name);

        if (cname == null)
            throw new NamingException ("Name is null");

        if (cname.size() == 0)
            throw new NamingException ("Name is empty");


        //if no subcontexts, just bind it
        if (cname.size() == 1)
        {
            //get the object to be bound
            Object objToBind = NamingManager.getStateToBind(obj, name,this, _env);
            // Check for Referenceable
            if (objToBind instanceof Referenceable)
            {
                objToBind = ((Referenceable)objToBind).getReference();
            }

            //anything else we should be able to bind directly
            addBinding (cname, objToBind);
        }
        else
        {
            if(__log.isDebugEnabled())__log.debug("Checking for existing binding for name="+cname+" for first element of name="+cname.get(0));

            //walk down the subcontext hierarchy
            //need to ignore trailing empty "" name components

            String firstComponent = cname.get(0);
            Object ctx = null;

            if (firstComponent.equals(""))
                ctx = this;
            else
            {

                Binding  binding = getBinding (firstComponent);
                if (binding == null) {
                    if (_supportDeepBinding)
                    {
                        Name subname = _parser.parse(firstComponent);
                        Context subctx = new NamingContext((Hashtable)_env.clone(),firstComponent,this,_parser);
                        addBinding(subname,subctx);
                        binding = getBinding(subname);
                    }
                    else
                    {
                        throw new NameNotFoundException(firstComponent + " is not bound");
                    }
                }

                ctx = binding.getObject();

                if (ctx instanceof Reference)
                {
                    //deference the object
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                    }
                    catch (NamingException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        __log.warn("",e);
                        throw new NamingException (e.getMessage());
                    }
                }
            }


            if (ctx instanceof Context)
            {
                ((Context)ctx).bind (cname.getSuffix(1), obj);
            }
            else
                throw new NotContextException ("Object bound at "+firstComponent +" is not a Context");
        }
    }



    /*------------------------------------------------*/
    /**
     * Bind a name (as a String) to an object
     *
     * @param name a <code>String</code> value
     * @param obj an <code>Object</code> value
     * @exception NamingException if an error occurs
     */
    public void bind(String name, Object obj)
        throws NamingException
    {
        bind (_parser.parse(name), obj);
    }


    /*------------------------------------------------*/
    /**
     * Create a context as a child of this one
     *
     * @param name a <code>Name</code> value
     * @return a <code>Context</code> value
     * @exception NamingException if an error occurs
     */
    public Context createSubcontext (Name name)
        throws NamingException
    {
        if (isLocked())
        {
            NamingException ne = new NamingException ("This context is immutable");
            ne.setRemainingName(name);
            throw ne;
        }

        Name cname = toCanonicalName (name);

        if (cname == null)
            throw new NamingException ("Name is null");
        if (cname.size() == 0)
            throw new NamingException ("Name is empty");

        if (cname.size() == 1)
        {
            //not permitted to bind if something already bound at that name
            Binding binding = getBinding (cname);
            if (binding != null)
                throw new NameAlreadyBoundException (cname.toString());

            Context ctx = new NamingContext ((Hashtable)_env.clone(), cname.get(0), this, _parser);
            addBinding (cname, ctx);
            return ctx;
        }


        //If the name has multiple subcontexts, walk the hierarchy by
        //fetching the first one. All intermediate subcontexts in the
        //name must already exist.
        String firstComponent = cname.get(0);
        Object ctx = null;

        if (firstComponent.equals(""))
            ctx = this;
        else
        {
            Binding binding = getBinding (firstComponent);
            if (binding == null)
                throw new NameNotFoundException (firstComponent + " is not bound");

            ctx = binding.getObject();

            if (ctx instanceof Reference)
            {
                //deference the object
                if(__log.isDebugEnabled())__log.debug("Object bound at "+firstComponent +" is a Reference");
                try
                {
                    ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
                }
            }
        }

        if (ctx instanceof Context)
        {
            return ((Context)ctx).createSubcontext (cname.getSuffix(1));
        }
        else
            throw new NotContextException (firstComponent +" is not a Context");
    }


    /*------------------------------------------------*/
    /**
     * Create a Context as a child of this one
     *
     * @param name a <code>String</code> value
     * @return a <code>Context</code> value
     * @exception NamingException if an error occurs
     */
    public Context createSubcontext (String name)
        throws NamingException
    {
        return createSubcontext(_parser.parse(name));
    }



    /*------------------------------------------------*/
    /**
     *
     *
     * @param name name of subcontext to remove
     * @exception NamingException if an error occurs
     */
    public void destroySubcontext (String name)
        throws NamingException
    {
        removeBinding(_parser.parse(name));
    }



    /*------------------------------------------------*/
    /**
     *
     *
     * @param name name of subcontext to remove
     * @exception NamingException if an error occurs
     */
    public void destroySubcontext (Name name)
        throws NamingException
    {
         removeBinding(name);
    }

    /*------------------------------------------------*/
    /**
     * Lookup a binding by name
     *
     * @param name name of bound object
     * @exception NamingException if an error occurs
     */
    public Object lookup(Name name)
        throws NamingException
    {
        if(__log.isDebugEnabled())__log.debug("Looking up name=\""+name+"\"");
        Name cname = toCanonicalName(name);

        if ((cname == null) || (cname.size() == 0))
        {
            __log.debug("Null or empty name, returning copy of this context");
            NamingContext ctx = new NamingContext (_env, _name, _parent, _parser);
            ctx._bindings=_bindings;
            return ctx;
        }



        if (cname.size() == 1)
        {
            Binding binding = getBinding (cname);
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
                    return this.lookup (linkName.substring(2));
                else
                {
                    //link name is absolute
                    InitialContext ictx = new InitialContext();
                    return ictx.lookup (linkName);
                }
            }
            else if (o instanceof Reference)
            {
                //deference the object
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
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
                }
            }
            else
                return o;
        }

        //it is a multipart name, recurse to the first subcontext

        String firstComponent = cname.get(0);
        Object ctx = null;

        if (firstComponent.equals(""))
            ctx = this;
        else
        {

            Binding binding = getBinding (firstComponent);
            if (binding == null)
            {
                NameNotFoundException nnfe = new NameNotFoundException();
                nnfe.setRemainingName(cname);
                throw nnfe;
            }

            //as we have bound a reference to an object factory
            //for the component specific contexts
            //at "comp" we need to resolve the reference
            ctx = binding.getObject();

            if (ctx instanceof Reference)
            {
                //deference the object
                try
                {
                    ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
                }
            }
        }
        if (!(ctx instanceof Context))
            throw new NotContextException();

        return ((Context)ctx).lookup (cname.getSuffix(1));
    }


    /*------------------------------------------------*/
    /**
     * Lookup binding of an object by name
     *
     * @param name name of bound object
     * @return object bound to name
     * @exception NamingException if an error occurs
     */
    public Object lookup (String name)
        throws NamingException
    {
        return lookup (_parser.parse(name));
    }



    /*------------------------------------------------*/
    /**
     * Lookup link bound to name
     *
     * @param name name of link binding
     * @return LinkRef or plain object bound at name
     * @exception NamingException if an error occurs
     */
    public Object lookupLink (Name name)
        throws NamingException
    {
        Name cname = toCanonicalName(name);

        if (cname == null)
        {
            NamingContext ctx = new NamingContext (_env, _name, _parent, _parser);
            ctx._bindings=_bindings;
            return ctx;
        }
        if (cname.size() == 0)
            throw new NamingException ("Name is empty");

        if (cname.size() == 1)
        {
            Binding binding = getBinding (cname);
            if (binding == null)
                throw new NameNotFoundException();

            Object o = binding.getObject();

            //handle links by looking up the link
            if (o instanceof Reference)
            {
                //deference the object
                try
                {
                    return NamingManager.getObjectInstance(o, cname.getPrefix(1), this, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
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
        String firstComponent = cname.get(0);
        Object ctx = null;

        if (firstComponent.equals(""))
            ctx = this;
        else
        {
            Binding binding = getBinding (firstComponent);
            if (binding == null)
                throw new NameNotFoundException ();

            ctx = binding.getObject();

            if (ctx instanceof Reference)
            {
                //deference the object
                try
                {
                    ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
                }
            }
        }

        if (!(ctx instanceof Context))
            throw new NotContextException();

        return ((Context)ctx).lookup (cname.getSuffix(1));
    }


    /*------------------------------------------------*/
    /**
     * Lookup link bound to name
     *
     * @param name name of link binding
     * @return LinkRef or plain object bound at name
     * @exception NamingException if an error occurs
     */
    public Object lookupLink (String name)
        throws NamingException
    {
        return lookupLink (_parser.parse(name));
    }


    /*------------------------------------------------*/
    /**
     * List all names bound at Context named by Name
     *
     * @param name a <code>Name</code> value
     * @return a <code>NamingEnumeration</code> value
     * @exception NamingException if an error occurs
     */
    public NamingEnumeration list(Name name)
        throws NamingException
    {
        if(__log.isDebugEnabled())__log.debug("list() on Context="+getName()+" for name="+name);
        Name cname = toCanonicalName(name);



        if (cname == null)
        {
            return new NameEnumeration(__empty.iterator());
        }


        if (cname.size() == 0)
        {
           return new NameEnumeration (_bindings.values().iterator());
        }



        //multipart name
        String firstComponent = cname.get(0);
        Object ctx = null;

        if (firstComponent.equals(""))
            ctx = this;
        else
        {
            Binding binding = getBinding (firstComponent);
            if (binding == null)
                throw new NameNotFoundException ();

            ctx = binding.getObject();

            if (ctx instanceof Reference)
            {
                //deference the object
                if(__log.isDebugEnabled())__log.debug("Dereferencing Reference for "+name.get(0));
                try
                {
                    ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
                }
            }
        }

        if (!(ctx instanceof Context))
            throw new NotContextException();

        return ((Context)ctx).list (cname.getSuffix(1));
    }


    /*------------------------------------------------*/
    /**
     * List all names bound at Context named by Name
     *
     * @param name a <code>Name</code> value
     * @return a <code>NamingEnumeration</code> value
     * @exception NamingException if an error occurs
     */
    public NamingEnumeration list(String name)
        throws NamingException
    {
        return list(_parser.parse(name));
    }



    /*------------------------------------------------*/
    /**
     * List all Bindings present at Context named by Name
     *
     * @param name a <code>Name</code> value
     * @return a <code>NamingEnumeration</code> value
     * @exception NamingException if an error occurs
     */
    public NamingEnumeration listBindings(Name name)
        throws NamingException
    {
        Name cname = toCanonicalName (name);

        if (cname == null)
        {
            return new BindingEnumeration(__empty.iterator());
        }

        if (cname.size() == 0)
        {
           return new BindingEnumeration (_bindings.values().iterator());
        }



        //multipart name
        String firstComponent = cname.get(0);
        Object ctx = null;

        //if a name has a leading "/" it is parsed as "" so ignore it by staying
        //at this level in the tree
        if (firstComponent.equals(""))
            ctx = this;
        else
        {
            //it is a non-empty name component
            Binding binding = getBinding (firstComponent);
            if (binding == null)
                throw new NameNotFoundException ();

            ctx = binding.getObject();

            if (ctx instanceof Reference)
            {
                //deference the object
                try
                {
                    ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                }
                catch (NamingException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    __log.warn("",e);
                    throw new NamingException (e.getMessage());
                }
            }
        }

        if (!(ctx instanceof Context))
            throw new NotContextException();

        return ((Context)ctx).listBindings (cname.getSuffix(1));
    }



    /*------------------------------------------------*/
    /**
     * List all Bindings at Name
     *
     * @param name a <code>String</code> value
     * @return a <code>NamingEnumeration</code> value
     * @exception NamingException if an error occurs
     */
    public NamingEnumeration listBindings(String name)
        throws NamingException
    {
        return listBindings (_parser.parse(name));
    }


    /*------------------------------------------------*/
    /**
     * Overwrite or create a binding
     *
     * @param name a <code>Name</code> value
     * @param obj an <code>Object</code> value
     * @exception NamingException if an error occurs
     */
    public void rebind(Name name,
                       Object obj)
        throws NamingException
    {
        if (isLocked())
            throw new NamingException ("This context is immutable");

        Name cname = toCanonicalName(name);

        if (cname == null)
            throw new NamingException ("Name is null");

        if (cname.size() == 0)
            throw new NamingException ("Name is empty");


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
            addBinding (cname, objToBind);
        }
        else
        {
            //walk down the subcontext hierarchy
            if(__log.isDebugEnabled())__log.debug("Checking for existing binding for name="+cname+" for first element of name="+cname.get(0));

            String firstComponent = cname.get(0);
            Object ctx = null;


            if (firstComponent.equals(""))
                ctx = this;
            else
            {
                Binding  binding = getBinding (name.get(0));
                if (binding == null)
                    throw new NameNotFoundException (name.get(0)+ " is not bound");

                ctx = binding.getObject();


                if (ctx instanceof Reference)
                {
                    //deference the object
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                    }
                    catch (NamingException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        __log.warn("",e);
                        throw new NamingException (e.getMessage());
                    }
                }
            }

            if (ctx instanceof Context)
            {
                ((Context)ctx).rebind (cname.getSuffix(1), obj);
            }
            else
                throw new NotContextException ("Object bound at "+firstComponent +" is not a Context");
        }
    }


    /*------------------------------------------------*/
    /**
     * Overwrite or create a binding from Name to Object
     *
     * @param name a <code>String</code> value
     * @param obj an <code>Object</code> value
     * @exception NamingException if an error occurs
     */
    public void rebind (String name,
                        Object obj)
        throws NamingException
    {
        rebind (_parser.parse(name), obj);
    }

    /*------------------------------------------------*/
    /**
     * Not supported.
     *
     * @param name a <code>String</code> value
     * @exception NamingException if an error occurs
     */
    public void unbind (String name)
        throws NamingException
    {
        unbind(_parser.parse(name));
    }

    /*------------------------------------------------*/
    /**
     * Not supported.
     *
     * @param name a <code>String</code> value
     * @exception NamingException if an error occurs
     */
    public void unbind (Name name)
        throws NamingException
    {
        if (name.size() == 0)
            return;


        if (isLocked())
            throw new NamingException ("This context is immutable");

        Name cname = toCanonicalName(name);

        if (cname == null)
            throw new NamingException ("Name is null");

        if (cname.size() == 0)
            throw new NamingException ("Name is empty");


        //if no subcontexts, just unbind it
        if (cname.size() == 1)
        {
            removeBinding (cname);
        }
        else
        {
            //walk down the subcontext hierarchy
            if(__log.isDebugEnabled())__log.debug("Checking for existing binding for name="+cname+" for first element of name="+cname.get(0));

            String firstComponent = cname.get(0);
            Object ctx = null;


            if (firstComponent.equals(""))
                ctx = this;
            else
            {
                Binding  binding = getBinding (name.get(0));
                if (binding == null)
                    throw new NameNotFoundException (name.get(0)+ " is not bound");

                ctx = binding.getObject();

                if (ctx instanceof Reference)
                {
                    //deference the object
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), this, _env);
                    }
                    catch (NamingException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        __log.warn("",e);
                        throw new NamingException (e.getMessage());
                    }
                }
            }

            if (ctx instanceof Context)
            {
                ((Context)ctx).unbind (cname.getSuffix(1));
            }
            else
                throw new NotContextException ("Object bound at "+firstComponent +" is not a Context");
        }
    }

    /*------------------------------------------------*/
    /**
     * Not supported
     *
     * @param oldName a <code>Name</code> value
     * @param newName a <code>Name</code> value
     * @exception NamingException if an error occurs
     */
    public void rename(Name oldName,
                       Name newName)
        throws NamingException
    {
        throw new OperationNotSupportedException();
    }


    /*------------------------------------------------*/
    /**
     * Not supported
     *
     * @param oldName a <code>Name</code> value
     * @param newName a <code>Name</code> value
     * @exception NamingException if an error occurs
     */    public void rename(String oldName,
                              String newName)
     throws NamingException
     {
         throw new OperationNotSupportedException();
     }



    /*------------------------------------------------*/
    /** Join two names together. These are treated as
     * CompoundNames.
     *
     * @param name a <code>Name</code> value
     * @param prefix a <code>Name</code> value
     * @return a <code>Name</code> value
     * @exception NamingException if an error occurs
     */
    public Name composeName(Name name,
                            Name prefix)
        throws NamingException
    {
        if (name == null)
            throw new NamingException ("Name cannot be null");
        if (prefix == null)
            throw new NamingException ("Prefix cannot be null");

        Name compoundName = (CompoundName)prefix.clone();
        compoundName.addAll (name);
        return compoundName;
    }



    /*------------------------------------------------*/
    /** Join two names together. These are treated as
     * CompoundNames.
     *
     * @param name a <code>Name</code> value
     * @param prefix a <code>Name</code> value
     * @return a <code>Name</code> value
     * @exception NamingException if an error occurs
     */
    public String composeName (String name,
                               String prefix)
        throws NamingException
    {
        if (name == null)
            throw new NamingException ("Name cannot be null");
        if (prefix == null)
            throw new NamingException ("Prefix cannot be null");

        Name compoundName = _parser.parse(prefix);
        compoundName.add (name);
        return compoundName.toString();
    }


    /*------------------------------------------------*/
    /**
     * Do nothing
     *
     * @exception NamingException if an error occurs
     */
    public void close ()
        throws NamingException
    {
    }


    /*------------------------------------------------*/
    /**
     * Return a NameParser for this Context.
     *
     * @param name a <code>Name</code> value
     * @return a <code>NameParser</code> value
     */
    public NameParser getNameParser (Name name)
    {
        return _parser;
    }

    /*------------------------------------------------*/
    /**
     * Return a NameParser for this Context.
     *
     * @param name a <code>Name</code> value
     * @return a <code>NameParser</code> value
     */
    public NameParser getNameParser (String name)
    {
        return _parser;
    }


    /*------------------------------------------------*/
    /**
     * Get the full name of this Context node
     * by visiting it's ancestors back to root.
     *
     * NOTE: if this Context has a URL namespace then
     * the URL prefix will be missing
     *
     * @return the full name of this Context
     * @exception NamingException if an error occurs
     */
    public String getNameInNamespace ()
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


    /*------------------------------------------------*/
    /**
     * Add an environment setting to this Context
     *
     * @param propName name of the property to add
     * @param propVal value of the property to add
     * @return propVal or previous value of the property
     * @exception NamingException if an error occurs
     */
    public Object addToEnvironment(String propName,
                                   Object propVal)
        throws NamingException
    {
        if (isLocked() && !(propName.equals(UNLOCK_PROPERTY)))
            throw new NamingException ("This context is immutable");

        return _env.put (propName, propVal);
    }


    /*------------------------------------------------*/
    /**
     * Remove a property from this Context's environment.
     *
     * @param propName name of property to remove
     * @return value of property or null if it didn't exist
     * @exception NamingException if an error occurs
     */
    public Object removeFromEnvironment(String propName)
        throws NamingException
    {
        if (isLocked())
            throw new NamingException ("This context is immutable");

        return _env.remove (propName);
    }


    /*------------------------------------------------*/
    /**
     * Get the environment of this Context.
     *
     * @return a copy of the environment of this Context.
     */
    public Hashtable getEnvironment ()
    {
        return (Hashtable)_env.clone();
    }

    /*------------------------------------------------*/
    /**
     * Add a name to object binding to this Context.
     *
     * @param name a <code>Name</code> value
     * @param obj an <code>Object</code> value
     * @throws NameAlreadyBoundException if name already bound
     */
    public void addBinding (Name name, Object obj) throws NameAlreadyBoundException
    {
        String key = name.toString();
        Binding binding=new Binding (key, obj);

        Collection<Listener> list = findListeners();

        for (Listener listener : list)
        {
            binding=listener.bind(this,binding);
            if (binding==null)
                break;
        }

        if(__log.isDebugEnabled())
            __log.debug("Adding binding with key="+key+" obj="+obj+" for context="+_name+" as "+binding);

        if (binding!=null)
        {
            if (_bindings.containsKey(key)) {
                if(_supportDeepBinding) {
                    // quietly return (no exception)
                    // this is jndi spec breaking, but is added to support broken
                    // jndi users like openejb.
                    return;
                }
                throw new NameAlreadyBoundException(name.toString());
            }
            _bindings.put(key,binding);
        }
    }

    /*------------------------------------------------*/
    /**
     * Get a name to object binding from this Context
     *
     * @param name a <code>Name</code> value
     * @return a <code>Binding</code> value
     */
    public Binding getBinding (Name name)
    {
        return (Binding) _bindings.get(name.toString());
    }


    /*------------------------------------------------*/
    /**
     * Get a name to object binding from this Context
     *
     * @param name as a String
     * @return null or the Binding
     */
    public Binding getBinding (String name)
    {
        return (Binding) _bindings.get(name);
    }

    /*------------------------------------------------*/
    public void removeBinding (Name name)
    {
        String key = name.toString();
        if (__log.isDebugEnabled())
            __log.debug("Removing binding with key="+key);
        Binding binding = _bindings.remove(key);
        if (binding!=null)
        {
            Collection<Listener> list = findListeners();
            for (Listener listener : list)
                listener.unbind(this,binding);
        }
    }

    /*------------------------------------------------*/
    /**
     * Remove leading or trailing empty components from
     * name. Eg "/comp/env/" -&gt; "comp/env"
     *
     * @param name the name to normalize
     * @return normalized name
     */
    public Name toCanonicalName (Name name)
    {
        Name canonicalName = name;

        if (name != null)
        {
            if (canonicalName.size() > 1)
            {
                if (canonicalName.get(0).equals(""))
                    canonicalName = canonicalName.getSuffix(1);

                if (canonicalName.get(canonicalName.size()-1).equals(""))
                    canonicalName = canonicalName.getPrefix(canonicalName.size()-1);
            }
        }

        return canonicalName;
    }

    /* ------------------------------------------------------------ */
    public boolean isLocked()
    {
       if ((_env.get(LOCK_PROPERTY) == null) && (_env.get(UNLOCK_PROPERTY) == null))
           return false;

       Object lockKey = _env.get(LOCK_PROPERTY);
       Object unlockKey = _env.get(UNLOCK_PROPERTY);

       if ((lockKey != null) && (unlockKey != null) && (lockKey.equals(unlockKey)))
           return false;
       return true;
    }


    /* ------------------------------------------------------------ */
    public String dump()
    {
        StringBuilder buf = new StringBuilder();
        try
        {
            dump(buf,"");
        }
        catch(Exception e)
        {
            __log.warn(e);
        }
        return buf.toString();
    }


    /* ------------------------------------------------------------ */
    public void dump(Appendable out,String indent) throws IOException
    {
        out.append(this.getClass().getSimpleName()).append("@").append(Long.toHexString(this.hashCode())).append("\n");
        int size=_bindings.size();
        int i=0;
        for (Map.Entry<String,Binding> entry : ((Map<String,Binding>)_bindings).entrySet())
        {
            boolean last=++i==size;
            out.append(indent).append(" +- ").append(entry.getKey()).append(": ");

            Binding binding = entry.getValue();
            Object value = binding.getObject();

            if ("comp".equals(entry.getKey()) && value instanceof Reference && "org.eclipse.jetty.jndi.ContextFactory".equals(((Reference)value).getFactoryClassName()))
            {
                ContextFactory.dump(out,indent+(last?"    ":" |  "));
            }
            else if (value instanceof Dumpable)
            {
                ((Dumpable)value).dump(out,indent+(last?"    ":" |  "));
            }
            else
            {
                out.append(value.getClass().getSimpleName()).append("=");
                out.append(String.valueOf(value).replace('\n','|').replace('\r','|'));
                out.append("\n");
            }
        }
    }

    private Collection<Listener> findListeners()
    {
        Collection<Listener> list = new ArrayList<Listener>();
        NamingContext ctx=this;
        while (ctx!=null)
        {
            if (ctx._listeners!=null)
                list.addAll(ctx._listeners);
            ctx=(NamingContext)ctx.getParent();
        }
        return list;
    }

    public void addListener(Listener listener)
    {
        if (_listeners==null)
            _listeners=new ArrayList<Listener>();
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
