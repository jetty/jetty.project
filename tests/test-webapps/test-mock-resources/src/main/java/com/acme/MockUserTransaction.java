//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
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

    /**
     * @see javax.transaction.UserTransaction#begin()
     */
    @Override
    public void begin() throws NotSupportedException, SystemException
    {
    }

    /**
     * @see javax.transaction.UserTransaction#commit()
     */
    @Override
    public void commit() throws HeuristicMixedException,
        HeuristicRollbackException, IllegalStateException,
        RollbackException, SecurityException, SystemException
    {
    }

    /**
     * @see javax.transaction.UserTransaction#getStatus()
     */
    @Override
    public int getStatus() throws SystemException
    {
        return 0;
    }

    /**
     * @see javax.transaction.UserTransaction#rollback()
     */
    @Override
    public void rollback() throws IllegalStateException, SecurityException,
        SystemException
    {
    }

    /**
     * @see javax.transaction.UserTransaction#setRollbackOnly()
     */
    @Override
    public void setRollbackOnly() throws IllegalStateException, SystemException
    {
    }

    /**
     * @see javax.transaction.UserTransaction#setTransactionTimeout(int)
     */
    @Override
    public void setTransactionTimeout(int arg0) throws SystemException
    {
    }
}
