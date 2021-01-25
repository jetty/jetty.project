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
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.transaction.UserTransaction;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Transaction
 *
 * Class to represent a JTA UserTransaction impl.
 */
public class Transaction extends NamingEntry
{
    private static final Logger LOG = Log.getLogger(Transaction.class);
    public static final String USER_TRANSACTION = "UserTransaction";

    public static void bindToENC()
        throws NamingException
    {
        Transaction txEntry = (Transaction)NamingEntryUtil.lookupNamingEntry(null, Transaction.USER_TRANSACTION);

        if (txEntry != null)
        {
            txEntry.bindToComp();
        }
        else
        {
            throw new NameNotFoundException(USER_TRANSACTION + " not found");
        }
    }

    public Transaction(UserTransaction userTransaction)
        throws NamingException
    {
        super(USER_TRANSACTION);
        save(userTransaction);
    }

    /**
     * Allow other bindings of UserTransaction.
     *
     * These should be in ADDITION to java:comp/UserTransaction
     *
     * @see NamingEntry#bindToENC(java.lang.String)
     */
    @Override
    public void bindToENC(String localName)
        throws NamingException
    {
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp/env");
        if (LOG.isDebugEnabled())
            LOG.debug("Binding java:comp/env" + getJndiName() + " to " + _objectNameString);
        NamingUtil.bind(env, localName, new LinkRef(_objectNameString));
    }

    /**
     * Insist on the java:comp/UserTransaction binding
     */
    private void bindToComp()
        throws NamingException
    {
        //ignore the name, it is always bound to java:comp
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp");
        if (LOG.isDebugEnabled())
            LOG.debug("Binding java:comp/" + getJndiName() + " to " + _objectNameString);
        NamingUtil.bind(env, getJndiName(), new LinkRef(_objectNameString));
    }

    /**
     * Unbind this Transaction from a java:comp
     */
    @Override
    public void unbindENC()
    {
        try
        {
            InitialContext ic = new InitialContext();
            Context env = (Context)ic.lookup("java:comp");
            if (LOG.isDebugEnabled())
                LOG.debug("Unbinding java:comp/" + getJndiName());
            env.unbind(getJndiName());
        }
        catch (NamingException e)
        {
            LOG.warn(e);
        }
    }
}
