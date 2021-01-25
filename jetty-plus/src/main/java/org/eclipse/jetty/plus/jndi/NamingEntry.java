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

package org.eclipse.jetty.plus.jndi;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.LinkRef;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingException;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * NamingEntry
 * <p>
 * Base class for all jndi related entities. Instances of
 * subclasses of this class are declared in jetty.xml or in a
 * webapp's WEB-INF/jetty-env.xml file.
 * <p>
 * NOTE: that all NamingEntries will be bound in a single namespace.
 * The "global" level is just in the top level context. The "local"
 * level is a context specific to a webapp.
 */
public abstract class NamingEntry
{
    private static final Logger LOG = Log.getLogger(NamingEntry.class);
    public static final String __contextName = "__"; //all NamingEntries stored in context called "__"
    protected final Object _scope;
    protected final String _jndiName;  //the name representing the object associated with the NamingEntry
    protected String _namingEntryNameString; //the name of the NamingEntry relative to the context it is stored in
    protected String _objectNameString; //the name of the object relative to the context it is stored in

    /**
     * Create a naming entry.
     *
     * @param scope an object representing the scope of the name to be bound into jndi, where null means jvm scope.
     * @param jndiName the name that will be associated with an object bound into jndi
     * @throws NamingException if jndiName is null
     */
    protected NamingEntry(Object scope, String jndiName)
        throws NamingException
    {
        if (jndiName == null)
            throw new NamingException("jndi name is null");
        this._scope = scope;
        this._jndiName = jndiName;
    }

    /**
     * Create a NamingEntry.
     * A NamingEntry is a name associated with a value which can later
     * be looked up in JNDI by a webapp.
     *
     * We create the NamingEntry and put it into JNDI where it can
     * be linked to the webapp's env-entry, resource-ref etc entries.
     *
     * @param jndiName the name of the object which will eventually be in java:comp/env
     * @throws NamingException if unable to create naming entry
     */
    protected NamingEntry(String jndiName)
        throws NamingException
    {
        this(null, jndiName);
    }

    /**
     * Add a <code>java:comp/env</code> binding for the object represented by this NamingEntry,
     * but bind it as the name supplied
     *
     * @param localName the local name to bind
     * @throws NamingException if unable to bind
     */
    public void bindToENC(String localName)
        throws NamingException
    {
        // TODO - check on the whole overriding/non-overriding thing
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp/env");
        if (LOG.isDebugEnabled())
            LOG.debug("Binding java:comp/env/" + localName + " to " + _objectNameString);
        NamingUtil.bind(env, localName, new LinkRef(_objectNameString));
    }

    /**
     * Unbind this NamingEntry from a java:comp/env
     */
    public void unbindENC()
    {
        try
        {
            InitialContext ic = new InitialContext();
            Context env = (Context)ic.lookup("java:comp/env");
            if (LOG.isDebugEnabled())
                LOG.debug("Unbinding java:comp/env/" + getJndiName());
            env.unbind(getJndiName());
        }
        catch (NamingException e)
        {
            LOG.warn(e);
        }
    }

    /**
     * Unbind this NamingEntry entirely
     */
    public void release()
    {
        try
        {
            InitialContext ic = new InitialContext();
            ic.unbind(_objectNameString);
            ic.unbind(_namingEntryNameString);
            this._namingEntryNameString = null;
            this._objectNameString = null;
        }
        catch (NamingException e)
        {
            LOG.warn(e);
        }
    }

    /**
     * Get the unique name of the object
     * relative to the scope
     *
     * @return the unique jndi name of the object
     */
    public String getJndiName()
    {
        return _jndiName;
    }

    /**
     * Get the name of the object, fully
     * qualified with the scope
     *
     * @return the name of the object, fully qualified with the scope
     */
    public String getJndiNameInScope()
    {
        return _objectNameString;
    }

    /**
     * Save the NamingEntry for later use.
     * <p>
     * Saving is done by binding both the NamingEntry
     * itself, and the value it represents into
     * JNDI. In this way, we can link to the
     * value it represents later, but also
     * still retrieve the NamingEntry itself too.
     * <p>
     * The object is bound at scope/jndiName and
     * the NamingEntry is bound at scope/__/jndiName.
     * <p>
     * eg
     * <pre>
     * jdbc/foo    : DataSource
     * __/jdbc/foo : NamingEntry
     * </pre>
     *
     * @param object the object to save
     * @throws NamingException if unable to save
     * @see NamingEntryUtil#getNameForScope(Object)
     */
    protected void save(Object object)
        throws NamingException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("SAVE {} in {}", this, _scope);
        InitialContext ic = new InitialContext();
        NameParser parser = ic.getNameParser("");
        Name prefix = NamingEntryUtil.getNameForScope(_scope);

        //bind the NamingEntry into the context
        Name namingEntryName = NamingEntryUtil.makeNamingEntryName(parser, getJndiName());
        namingEntryName.addAll(0, prefix);
        _namingEntryNameString = namingEntryName.toString();
        NamingUtil.bind(ic, _namingEntryNameString, this);

        //bind the object as well
        Name objectName = parser.parse(getJndiName());
        objectName.addAll(0, prefix);
        _objectNameString = objectName.toString();
        NamingUtil.bind(ic, _objectNameString, object);
    }

    protected String toStringMetaData()
    {
        return null;
    }

    @Override
    public String toString()
    {
        String metadata = toStringMetaData();
        if (metadata == null)
            return String.format("%s@%x{name=%s}", this.getClass().getName(), hashCode(), getJndiName());
        return String.format("%s@%x{name=%s,%s}", this.getClass().getName(), hashCode(), getJndiName(), metadata);
    }
}
