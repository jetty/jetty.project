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

package org.eclipse.jetty.jndi.local;

import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
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

import org.eclipse.jetty.jndi.BindingEnumeration;
import org.eclipse.jetty.jndi.NameEnumeration;
import org.eclipse.jetty.jndi.NamingContext;
import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.log.Logger;

/**
 *
 * localContext
 *
 * Implementation of the delegate for InitialContext for the local namespace.
 *
 *
 * @version $Revision: 4780 $ $Date: 2009-03-17 16:36:08 +0100 (Tue, 17 Mar 2009) $
 *
 */
public class localContextRoot implements Context
{
    private final static Logger __log=NamingUtil.__log;
    protected final static NamingContext __root = new NamingRoot();
    private final Hashtable<String,Object> _env;


    static class NamingRoot extends NamingContext
    {
        public NamingRoot()
        {
            super (null,null,null,new LocalNameParser());
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

    /**
     *
     *
     * @see javax.naming.Context#close()
     */
    public void close() throws NamingException
    {

    }

    /**
     *
     *
     * @see javax.naming.Context#getNameInNamespace()
     */
    public String getNameInNamespace() throws NamingException
    {
        return "";
    }


    /**
     *
     *
     * @see javax.naming.Context#destroySubcontext(javax.naming.Name)
     */
    public void destroySubcontext(Name name) throws NamingException
    {
        synchronized (__root)
        {
            __root.destroySubcontext(getSuffix(name));
        }
    }


    /**
     *
     *
     * @see javax.naming.Context#destroySubcontext(java.lang.String)
     */
    public void destroySubcontext(String name) throws NamingException
    {
        synchronized (__root)
        {

           destroySubcontext(__root.getNameParser("").parse(getSuffix(name)));
        }
    }


    /**
     *
     *
     * @see javax.naming.Context#getEnvironment()
     */
    public Hashtable getEnvironment() throws NamingException
    {
        return _env;
    }



    /**
     *
     *
     * @see javax.naming.Context#unbind(javax.naming.Name)
     */
    public void unbind(Name name) throws NamingException
    {
        synchronized (__root)
        {
            //__root.unbind(getSuffix(name));

            if (name.size() == 0)
                return;


            if (__root.isLocked())
                throw new NamingException ("This context is immutable");

            Name cname = __root.toCanonicalName(name);

            if (cname == null)
                throw new NamingException ("Name is null");

            if (cname.size() == 0)
                throw new NamingException ("Name is empty");


            //if no subcontexts, just unbind it
            if (cname.size() == 1)
            {
                __root.removeBinding (cname);
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
                    Binding  binding = __root.getBinding (name.get(0));
                    if (binding == null)
                        throw new NameNotFoundException (name.get(0)+ " is not bound");

                    ctx = binding.getObject();

                    if (ctx instanceof Reference)
                    {
                        //deference the object
                        try
                        {
                            ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }

    /**
     *
     *
     * @see javax.naming.Context#unbind(java.lang.String)
     */
    public void unbind(String name) throws NamingException
    {
        unbind(__root.getNameParser("").parse(getSuffix(name)));
    }



    /**
     *
     *
     * @see javax.naming.Context#lookupLink(java.lang.String)
     */
    public Object lookupLink(String name) throws NamingException
    {
        synchronized (__root)
        {
            return lookupLink(__root.getNameParser("").parse(getSuffix(name)));
        }
    }

    /**
     *
     *
     * @see javax.naming.Context#lookupLink(javax.naming.Name)
     */
    public Object lookupLink(Name name) throws NamingException
    {
        synchronized (__root)
        {
            //return __root.lookupLink(getSuffix(name));


            Name cname = __root.toCanonicalName(name);

            if (cname == null)
            {
                //If no name create copy of this context with same bindings, but with copy of the environment so it can be modified
                NamingContext ctx = new NamingContext (_env, null, null, __root.getNameParser(""));
                ctx.setBindings(__root.getBindings());
                return ctx;
            }

            if (cname.size() == 0)
                throw new NamingException ("Name is empty");

            if (cname.size() == 1)
            {
                Binding binding = __root.getBinding (cname);
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
                Binding binding = __root.getBinding (firstComponent);
                if (binding == null)
                    throw new NameNotFoundException ();

                ctx = binding.getObject();

                if (ctx instanceof Reference)
                {
                    //deference the object
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }


    /**
     *
     *
     * @see javax.naming.Context#removeFromEnvironment(java.lang.String)
     */
    public Object removeFromEnvironment(String propName) throws NamingException
    {
        return _env.remove(propName);
    }


    /**
     *
     *
     * @see javax.naming.Context#lookup(javax.naming.Name)
     */
    public Object lookup(Name name) throws NamingException
    {
        synchronized (__root)
        {
            //return __root.lookup(getSuffix(name));

            if(__log.isDebugEnabled())__log.debug("Looking up name=\""+name+"\"");
            Name cname = __root.toCanonicalName(name);

            if ((cname == null) || (cname.size() == 0))
            {
                __log.debug("Null or empty name, returning copy of this context");
                NamingContext ctx = new NamingContext (_env, null, null, __root.getNameParser(""));
                ctx.setBindings(__root.getBindings());
                return ctx;
            }



            if (cname.size() == 1)
            {
                Binding binding = __root.getBinding (cname);
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
                        return lookup (linkName.substring(2));
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
                        return NamingManager.getObjectInstance(o, cname, __root, _env);
                    }
                    catch (NamingException e)
                    {
                        throw e;
                    }
                    catch (final Exception e)
                    {
                        throw new NamingException (e.getMessage())
                        {
                            { initCause(e);}
                        };
                    }
                }
                else
                    return o;
            }

            //it is a multipart name, get the first subcontext

            String firstComponent = cname.get(0);
            Object ctx = null;

            if (firstComponent.equals(""))
                ctx = this;
            else
            {

                Binding binding = __root.getBinding (firstComponent);
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
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }


    /**
     *
     *
     * @see javax.naming.Context#lookup(java.lang.String)
     */
    public Object lookup(String name) throws NamingException
    {
        synchronized (__root)
        {
            return lookup(__root.getNameParser("").parse(getSuffix(name)));
        }
    }


    /**
     *
     *
     * @see javax.naming.Context#bind(java.lang.String, java.lang.Object)
     */
    public void bind(String name, Object obj) throws NamingException
    {
        synchronized (__root)
        {
           bind(__root.getNameParser("").parse(getSuffix(name)), obj);

        }
    }


    /**
     *
     *
     * @see javax.naming.Context#bind(javax.naming.Name, java.lang.Object)
     */
    public void bind(Name name, Object obj) throws NamingException
    {
        synchronized (__root)
        {
           // __root.bind(getSuffix(name), obj);


            if (__root.isLocked())
                throw new NamingException ("This context is immutable");

            Name cname = __root.toCanonicalName(name);

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
                __root.addBinding (cname, objToBind);
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

                    Binding  binding = __root.getBinding (firstComponent);
                    if (binding == null)
                        throw new NameNotFoundException (firstComponent+ " is not bound");

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
    }

    /**
     *
     *
     * @see javax.naming.Context#rebind(javax.naming.Name, java.lang.Object)
     */
    public void rebind(Name name, Object obj) throws NamingException
    {
        synchronized (__root)
        {
            //__root.rebind(getSuffix(name), obj);


            if (__root.isLocked())
                throw new NamingException ("This context is immutable");

            Name cname = __root.toCanonicalName(name);

            if (cname == null)
                throw new NamingException ("Name is null");

            if (cname.size() == 0)
                throw new NamingException ("Name is empty");


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
                __root.addBinding (cname, objToBind);
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
                    Binding  binding = __root.getBinding (name.get(0));
                    if (binding == null)
                        throw new NameNotFoundException (name.get(0)+ " is not bound");

                    ctx = binding.getObject();


                    if (ctx instanceof Reference)
                    {
                        //deference the object
                        try
                        {
                            ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }

    /**
     *
     *
     * @see javax.naming.Context#rebind(java.lang.String, java.lang.Object)
     */
    public void rebind(String name, Object obj) throws NamingException
    {
        synchronized (__root)
        {
            rebind(__root.getNameParser("").parse(getSuffix(name)), obj);
        }
    }
    /**
     *
     *
     * @see javax.naming.Context#rename(javax.naming.Name, javax.naming.Name)
     */
    public void rename(Name oldName, Name newName) throws NamingException
    {
        synchronized (__root)
        {
            throw new OperationNotSupportedException();
        }
    }

    /**
     *
     *
     * @see javax.naming.Context#rename(java.lang.String, java.lang.String)
     */
    public void rename(String oldName, String newName) throws NamingException
    {
        synchronized (__root)
        {
           throw new OperationNotSupportedException();
        }
    }

    /**
     *
     *
     * @see javax.naming.Context#createSubcontext(java.lang.String)
     */
    public Context createSubcontext(String name) throws NamingException
    {
        synchronized (__root)
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
    }

    /**
     *
     *
     * @see javax.naming.Context#createSubcontext(javax.naming.Name)
     */
    public Context createSubcontext(Name name) throws NamingException
    {
        synchronized (__root)
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
                NamingException ne = new NamingException ("This context is immutable");
                ne.setRemainingName(name);
                throw ne;
            }

            Name cname = __root.toCanonicalName (name);

            if (cname == null)
                throw new NamingException ("Name is null");
            if (cname.size() == 0)
                throw new NamingException ("Name is empty");

            if (cname.size() == 1)
            {
                //not permitted to bind if something already bound at that name
                Binding binding = __root.getBinding (cname);
                if (binding != null)
                    throw new NameAlreadyBoundException (cname.toString());

                //make a new naming context with the root as the parent
                Context ctx = new NamingContext ((Hashtable)_env.clone(), cname.get(0), __root,  __root.getNameParser(""));
                __root.addBinding (cname, ctx);
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
                Binding binding = __root.getBinding (firstComponent);
                if (binding == null)
                    throw new NameNotFoundException (firstComponent + " is not bound");

                ctx = binding.getObject();

                if (ctx instanceof Reference)
                {
                    //deference the object
                    if(__log.isDebugEnabled())__log.debug("Object bound at "+firstComponent +" is a Reference");
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }


    /**
     *
     *
     * @see javax.naming.Context#getNameParser(java.lang.String)
     */
    public NameParser getNameParser(String name) throws NamingException
    {
        return __root.getNameParser(name);
    }

    /**
     *
     *
     * @see javax.naming.Context#getNameParser(javax.naming.Name)
     */
    public NameParser getNameParser(Name name) throws NamingException
    {
        return __root.getNameParser(name);
    }

    /**
     *
     *
     * @see javax.naming.Context#list(java.lang.String)
     */
    public NamingEnumeration list(String name) throws NamingException
    {
        synchronized (__root)
        {
            return list(__root.getNameParser("").parse(getSuffix(name)));
        }
    }


    /**
     *
     *
     * @see javax.naming.Context#list(javax.naming.Name)
     */
    public NamingEnumeration list(Name name) throws NamingException
    {
        synchronized (__root)
        {
            //return __root.list(getSuffix(name));


            Name cname = __root.toCanonicalName(name);

            if (cname == null)
            {
                List<Binding> empty = Collections.emptyList();
                return new NameEnumeration(empty.iterator());
            }


            if (cname.size() == 0)
            {
               return new NameEnumeration (__root.getBindings().values().iterator());
            }



            //multipart name
            String firstComponent = cname.get(0);
            Object ctx = null;

            if (firstComponent.equals(""))
                ctx = this;
            else
            {
                Binding binding = __root.getBinding (firstComponent);
                if (binding == null)
                    throw new NameNotFoundException ();

                ctx = binding.getObject();

                if (ctx instanceof Reference)
                {
                    //deference the object
                    if(__log.isDebugEnabled())__log.debug("Dereferencing Reference for "+name.get(0));
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }

    /**
     *
     *
     * @see javax.naming.Context#listBindings(javax.naming.Name)
     */
    public NamingEnumeration listBindings(Name name) throws NamingException
    {
        synchronized (__root)
        {
            //return __root.listBindings(getSuffix(name));

            Name cname = __root.toCanonicalName (name);

            if (cname == null)
            {
                List<Binding> empty = Collections.emptyList();
                return new BindingEnumeration(empty.iterator());
            }

            if (cname.size() == 0)
            {
               return new BindingEnumeration (__root.getBindings().values().iterator());
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
                Binding binding = __root.getBinding (firstComponent);
                if (binding == null)
                    throw new NameNotFoundException ();

                ctx = binding.getObject();

                if (ctx instanceof Reference)
                {
                    //deference the object
                    try
                    {
                        ctx = NamingManager.getObjectInstance(ctx, getNameParser("").parse(firstComponent), __root, _env);
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
    }


    /**
     *
     *
     * @see javax.naming.Context#listBindings(java.lang.String)
     */
    public NamingEnumeration listBindings(String name) throws NamingException
    {
        synchronized (__root)
        {
            return listBindings(__root.getNameParser("").parse(getSuffix(name)));
        }
    }


    /**
     *
     *
     * @see javax.naming.Context#addToEnvironment(java.lang.String,
     *      java.lang.Object)
     */
    public Object addToEnvironment(String propName, Object propVal)
            throws NamingException
    {
        return _env.put(propName, propVal);
    }

    /**
     *
     *
     * @see javax.naming.Context#composeName(java.lang.String, java.lang.String)
     */
    public String composeName(String name, String prefix)
            throws NamingException
    {
        return __root.composeName(name, prefix);
    }

    /**
     *
     *
     * @see javax.naming.Context#composeName(javax.naming.Name,
     *      javax.naming.Name)
     */
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
