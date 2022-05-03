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

package org.eclipse.jetty.session;

import java.io.File;

/**
 * FileSessionDataStoreFactory
 */
public class FileSessionDataStoreFactory extends AbstractSessionDataStoreFactory
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

    @Override
    public SessionDataStore getSessionDataStore(SessionManager manager)
    {
        FileSessionDataStore fsds = new FileSessionDataStore();
        fsds.setDeleteUnrestorableFiles(isDeleteUnrestorableFiles());
        fsds.setStoreDir(getStoreDir());
        fsds.setGracePeriodSec(getGracePeriodSec());
        fsds.setSavePeriodSec(getSavePeriodSec());
        return fsds;
    }
}
