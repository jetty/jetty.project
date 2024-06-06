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

package org.example;

import javax.naming.Reference;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;

/**
 * MockUserTransaction
 */
public class MockUserTransaction extends Reference implements UserTransaction
{

    public MockUserTransaction()
    {
        super("org.example.MockUserTransaction", "org.example.MockUserTransactionFactory", null);
    }

    @Override
    public void begin() throws NotSupportedException, SystemException
    {
    }

    @Override
    public void commit() throws HeuristicMixedException,
        HeuristicRollbackException, IllegalStateException,
        RollbackException, SecurityException, SystemException
    {
    }

    @Override
    public int getStatus() throws SystemException
    {
        return 0;
    }

    @Override
    public void rollback() throws IllegalStateException, SecurityException,
        SystemException
    {
    }

    @Override
    public void setRollbackOnly() throws IllegalStateException, SystemException
    {
    }

    @Override
    public void setTransactionTimeout(int arg0) throws SystemException
    {
    }
}
