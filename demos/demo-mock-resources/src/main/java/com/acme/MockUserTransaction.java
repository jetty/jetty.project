//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package com.acme;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

/**
 * MockUserTransaction
 */
public class MockUserTransaction implements UserTransaction
{

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
