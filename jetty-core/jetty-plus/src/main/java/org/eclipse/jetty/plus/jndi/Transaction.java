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

package org.eclipse.jetty.plus.jndi;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.LinkRef;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;

import org.eclipse.jetty.util.jndi.NamingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction
 *
 * Class to represent a JTA UserTransaction impl.
 */
public class Transaction extends NamingEntry
{
    private static final Logger LOG = LoggerFactory.getLogger(Transaction.class);
    public static final String USER_TRANSACTION = "UserTransaction";

    public static void bindTransactionToENC(String scope)
        throws NamingException
    {
        NamingEntry txEntry = NamingEntryUtil.lookupNamingEntry(scope, Transaction.USER_TRANSACTION);

        if (txEntry instanceof Transaction)
        {
            ((Transaction)txEntry).bindToComp();
        }
        else
        {
            throw new NameNotFoundException(USER_TRANSACTION + " not found");
        }
    }

    /**
     * @param scope the environment in which to bind the UserTransaction
     * @param entry a UserTransaction or a Reference to a UserTransaction
     * @throws NamingException if there was a problem re
     */
    protected Transaction(String scope, Object entry)
        throws NamingException
    {
        super(scope, USER_TRANSACTION, entry);
    }

    /** 
     * @param scope the environment in which to bind the UserTransaction
     * @param userTransactionRef a Reference to a UserTransaction
     * @throws NamingException if there was a problem re
     */
    public Transaction(String scope, Reference userTransactionRef)
        throws NamingException
    {
        this(scope, (Object)userTransactionRef);
    }

    /**
     * @param scope the environment in which to bind the UserTransaction
     * @param userTransactionReferenceable a {@link Referenceable} to a UserTransaction
     * @throws NamingException if there was a problem re
     */
    public Transaction(String scope, Referenceable userTransactionReferenceable)
        throws NamingException
    {
        this(scope, (Object)userTransactionReferenceable.getReference());
    }

    /**
     * Allow other bindings of UserTransaction.
     *
     * These should be in ADDITION to java:comp/UserTransaction
     *
     * @see NamingEntry#bindToENC(String)
     */
    @Override
    public void bindToENC(String localName)
        throws NamingException
    {
        InitialContext ic = new InitialContext();
        Context env = (Context)ic.lookup("java:comp/env");
        if (LOG.isDebugEnabled())
            LOG.debug("Binding java:comp/env{} to {}", getJndiName(), _objectNameString);
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
            LOG.debug("Binding java:comp/{} to {}", getJndiName(), _objectNameString);
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
                LOG.debug("Unbinding java:comp/{}", getJndiName());
            env.unbind(getJndiName());
        }
        catch (NamingException e)
        {
            LOG.warn("Unable to unbind java:comp/{}", getJndiName(), e);
        }
    }
}
