//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session;

import java.io.File;

/**
 * FileSessionStoreFactory
 *
 *
 */
public class FileSessionStoreFactory extends AbstractSessionStoreFactory
{
    boolean _deleteUnrestorableFiles;
    File _storeDir;



    /**
     * @return the deleteUnrestorableFiles
     */
    public boolean isDeleteUnrestorableFiles()
    {
        return _deleteUnrestorableFiles;
    }


    /**
     * @param deleteUnrestorableFiles the deleteUnrestorableFiles to set
     */
    public void setDeleteUnrestorableFiles(boolean deleteUnrestorableFiles)
    {
        _deleteUnrestorableFiles = deleteUnrestorableFiles;
    }

    /**
     * @return the storeDir
     */
    public File getStoreDir()
    {
        return _storeDir;
    }


    /**
     * @param storeDir the storeDir to set
     */
    public void setStoreDir(File storeDir)
    {
        _storeDir = storeDir;
    }

    
    /** 
     * @see org.eclipse.jetty.server.session.SessionStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionStore getSessionStore(SessionHandler handler)
    {
        FileSessionStore fsds = new FileSessionStore();
        fsds.setDeleteUnrestorableFiles(isDeleteUnrestorableFiles());
        fsds.setStoreDir(getStoreDir());
        fsds.setGracePeriodSec(getGracePeriodSec());
        return fsds;
    }

}
