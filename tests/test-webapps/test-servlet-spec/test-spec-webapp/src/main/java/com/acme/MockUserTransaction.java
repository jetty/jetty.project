//========================================================================
//$Id: MockUserTransaction.java 1692 2007-03-23 04:33:07Z janb $
//Copyright 2006 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package com.acme;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

/**
 * MockUserTransaction
 *
 *
 */
public class MockUserTransaction implements UserTransaction
{

    /** 
     * @see javax.transaction.UserTransaction#begin()
     */
    public void begin() throws NotSupportedException, SystemException
    {
        // TODO Auto-generated method stub

    }

    /** 
     * @see javax.transaction.UserTransaction#commit()
     */
    public void commit() throws HeuristicMixedException,
            HeuristicRollbackException, IllegalStateException,
            RollbackException, SecurityException, SystemException
    {
        // TODO Auto-generated method stub

    }

    /** 
     * @see javax.transaction.UserTransaction#getStatus()
     */
    public int getStatus() throws SystemException
    {
        // TODO Auto-generated method stub
        return 0;
    }

    /** 
     * @see javax.transaction.UserTransaction#rollback()
     */
    public void rollback() throws IllegalStateException, SecurityException,
            SystemException
    {
        // TODO Auto-generated method stub

    }

    /** 
     * @see javax.transaction.UserTransaction#setRollbackOnly()
     */
    public void setRollbackOnly() throws IllegalStateException, SystemException
    {
        // TODO Auto-generated method stub

    }

    /** 
     * @see javax.transaction.UserTransaction#setTransactionTimeout(int)
     */
    public void setTransactionTimeout(int arg0) throws SystemException
    {
        // TODO Auto-generated method stub

    }

}
