//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session.x;



/**
 * JDBCSessionDataStore
 *
 *
 */
public class JDBCSessionDataStore extends AbstractSessionDataStore
{
    
    
    
    
    
    /**
     * JDBCSessionData
     *
     *
     */
    public class JDBCSessionData extends SessionData
    {
        protected String _rowId;
        //TODO other fields needed by jdbc
        
    
       

        /**
         * @param id
         * @param created
         * @param accessed
         * @param lastAccessed
         * @param maxInactiveMs
         */
        public JDBCSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
        {
            super(id, created, accessed, lastAccessed, maxInactiveMs);
        }
        
        

        public String getRowId()
        {
            return _rowId;
        }

        public void setRowId(String rowId)
        {
            _rowId = rowId;
        }
    }
    
    
    
    
    
    
    /**
     *
     */
    public JDBCSessionDataStore ()
    {
        super ();
    }

    
  

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new JDBCSessionData(id, created, accessed, lastAccessed, maxInactiveMs);
    }




    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        // TODO make jdbc calls to load in the session
        long created = 0;
        long accessed = 0;
        long lastAccessed = 0;
        long maxInactiveMs = 0;
        JDBCSessionData data = (JDBCSessionData)newSessionData(id, created, accessed, lastAccessed, maxInactiveMs);
        // set vhost etc
        // set row id
        // set expiry time
        return data;
    }

   

    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        // TODO delete from jdbc
        return false;
    }




    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionDataStore#doStore()
     */
    @Override
    public void doStore() throws Exception
    {
        // TODO write session data to jdbc
        
    }

}








