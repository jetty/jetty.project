// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.plus.jndi;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.LinkRef;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingException;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.log.Log;



/**
 * NamingEntry
 *
 * Base class for all jndi related entities. Instances of
 * subclasses of this class are declared in jetty.xml or in a 
 * webapp's WEB-INF/jetty-env.xml file.
 *
 * NOTE: that all NamingEntries will be bound in a single namespace.
 *  The "global" level is just in the top level context. The "local"
 *  level is a context specific to a webapp.
 */
public abstract class NamingEntry
{
    public static final String __contextName = "__"; //all NamingEntries stored in context called "__"
    protected String jndiName;  //the name representing the object associated with the NamingEntry
    protected Object objectToBind; //the object associated with the NamingEntry
    protected String namingEntryNameString; //the name of the NamingEntry relative to the context it is stored in
    protected String objectNameString; //the name of the object relative to the context it is stored in
   
   
  
 
    
    public NamingEntry (Object scope, String jndiName, Object object)
    throws NamingException
    {
        this.jndiName = jndiName;
        this.objectToBind = object;
        save(scope); 
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
     * @param object the object to be bound
     * @throws NamingException
     */
    public NamingEntry (String jndiName, Object object)
    throws NamingException
    {
        this (null, jndiName, object);
    }

    
 
    
    /**
     * Add a java:comp/env binding for the object represented by this NamingEntry,
     * but bind it as the name supplied
     * @throws NamingException
     */
    public void bindToENC(String localName)
    throws NamingException
    {
        //TODO - check on the whole overriding/non-overriding thing
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp/env");
        Log.debug("Binding java:comp/env/"+localName+" to "+objectNameString);
        NamingUtil.bind(env, localName, new LinkRef(objectNameString));
    }
    
    /**
     * Unbind this NamingEntry from a java:comp/env
     */
    public void unbindENC ()
    {
        try
        {
            InitialContext ic = new InitialContext();
            Context env = (Context)ic.lookup("java:comp/env");
            Log.debug("Unbinding java:comp/env/"+getJndiName());
            env.unbind(getJndiName());
        }
        catch (NamingException e)
        {
            Log.warn(e);
        }
    }
    
    /**
     * Unbind this NamingEntry entirely
     */
    public void release ()
    {
        try
        {
            InitialContext ic = new InitialContext();
            ic.unbind(objectNameString);
            ic.unbind(namingEntryNameString);
            this.jndiName=null;
            this.namingEntryNameString=null;
            this.objectNameString=null;
            this.objectToBind=null;
        }
        catch (NamingException e)
        {
            Log.warn(e);
        }
    }
    
    /**
     * Get the unique name of the object
     * relative to the scope
     * @return
     */
    public String getJndiName ()
    {
        return this.jndiName;
    }
    
    /**
     * Get the object that is to be bound
     * @return
     */
    public Object getObjectToBind()
    throws NamingException
    {   
        return this.objectToBind;
    }
    

    /**
     * Get the name of the object, fully
     * qualified with the scope
     * @return
     */
    public String getJndiNameInScope ()
    {
        return objectNameString;
    }
 
 
    
    /**
     * Save the NamingEntry for later use.
     * 
     * Saving is done by binding the NamingEntry
     * itself, and the value it represents into
     * JNDI. In this way, we can link to the
     * value it represents later, but also
     * still retrieve the NamingEntry itself too.
     * 
     * The object is bound at the jndiName passed in.
     * This NamingEntry is bound at __/jndiName.
     * 
     * eg
     * 
     * jdbc/foo    : DataSource
     * __/jdbc/foo : NamingEntry
     * 
     * @throws NamingException
     */
    protected void save (Object scope)
    throws NamingException
    {
        InitialContext ic = new InitialContext();
        NameParser parser = ic.getNameParser("");
        Name prefix = NamingEntryUtil.getNameForScope(scope);
      
        //bind the NamingEntry into the context
        Name namingEntryName = NamingEntryUtil.makeNamingEntryName(parser, getJndiName());
        namingEntryName.addAll(0, prefix);
        namingEntryNameString = namingEntryName.toString();
        NamingUtil.bind(ic, namingEntryNameString, this);
                
        //bind the object as well
        Name objectName = parser.parse(getJndiName());
        objectName.addAll(0, prefix);
        objectNameString = objectName.toString();
        NamingUtil.bind(ic, objectNameString, objectToBind);
    } 
    
}
