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
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.transaction.UserTransaction;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.log.Log;

/**
 * Transaction
 *
 * Class to represent a JTA UserTransaction impl.
 * 
 * 
 */
/**
 * Transaction
 *
 *
 */
public class Transaction extends NamingEntry
{
    public static final String USER_TRANSACTION = "UserTransaction";
    

    public static void bindToENC ()
    throws NamingException
    {
        Transaction txEntry = (Transaction)NamingEntryUtil.lookupNamingEntry(null, Transaction.USER_TRANSACTION);

        if ( txEntry != null )
        {
            txEntry.bindToComp();
        }
        else
        {
            throw new NameNotFoundException( USER_TRANSACTION + " not found" );
        }
    }
 
    
    
    public Transaction (UserTransaction userTransaction)
    throws NamingException
    {
        super (USER_TRANSACTION, userTransaction);           
    }
    
    
    /** 
     * Allow other bindings of UserTransaction.
     * 
     * These should be in ADDITION to java:comp/UserTransaction
     * @see org.eclipse.jetty.server.server.plus.jndi.NamingEntry#bindToENC(java.lang.String)
     */
    public void bindToENC (String localName)
    throws NamingException
    {   
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp/env");
        Log.debug("Binding java:comp/env"+getJndiName()+" to "+objectNameString);
        NamingUtil.bind(env, localName, new LinkRef(objectNameString));
    }
    
    /**
     * Insist on the java:comp/UserTransaction binding
     * @throws NamingException
     */
    private void bindToComp ()
    throws NamingException
    {   
        //ignore the name, it is always bound to java:comp
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp");
        Log.debug("Binding java:comp/"+getJndiName()+" to "+objectNameString);
        NamingUtil.bind(env, getJndiName(), new LinkRef(objectNameString));
    }
    
    /**
     * Unbind this Transaction from a java:comp
     */
    public void unbindENC ()
    {
        try
        {
            InitialContext ic = new InitialContext();
            Context env = (Context)ic.lookup("java:comp");
            Log.debug("Unbinding java:comp/"+getJndiName());
            env.unbind(getJndiName());
        }
        catch (NamingException e)
        {
            Log.warn(e);
        }
    }
}
