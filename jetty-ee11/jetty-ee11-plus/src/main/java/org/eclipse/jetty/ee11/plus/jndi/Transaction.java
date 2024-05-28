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

package org.eclipse.jetty.ee11.plus.jndi;

import javax.naming.NamingException;

import jakarta.transaction.UserTransaction;

/**
 * Transaction
 *
 * Class to represent a JTA UserTransaction impl.
 */
public class Transaction extends org.eclipse.jetty.plus.jndi.Transaction
{
    /**
     * @param scope the scope, usually an environment like ee9, ee10, ee11
     * @param userTransaction the UserTransaction
     * @throws NamingException if there was a problem registering the transaction
     */
    public Transaction(String scope, UserTransaction userTransaction)
        throws NamingException
    {
        super(scope, userTransaction);
    }
}
