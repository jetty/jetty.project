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

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * ScanTargetPattern
 *
 * Utility class to provide the ability for the mvn jetty:run 
 * mojo to be able to specify filesets of extra files to 
 * regularly scan for changes in order to redeploy the webapp.
 * 
 * For example:
 * 
 * &lt;scanTargetPattern&gt;
 *   &lt;directory&gt;/some/place&lt;/directory&gt;
 *   &lt;includes&gt;
 *     &lt;include&gt;some ant pattern here &lt;/include&gt;
 *     &lt;include&gt;some ant pattern here &lt;/include&gt; 
 *   &lt;/includes&gt;
 *   &lt;excludes&gt;
 *     &lt;exclude&gt;some ant pattern here &lt;/exclude&gt;
 *     &lt;exclude&gt;some ant pattern here &lt;/exclude&gt;
 *   &lt;/excludes&gt;
 * &lt;/scanTargetPattern&gt;
 */
public class ScanTargetPattern
{
    private File _directory;
    private List _includes = Collections.EMPTY_LIST;
    private List _excludes = Collections.EMPTY_LIST;

    /**
     * @return the _directory
     */
    public File getDirectory()
    {
        return _directory;
    }

    /**
     * @param directory the directory to set
     */
    public void setDirectory(File directory)
    {
        this._directory = directory;
    }
    
    public void setIncludes (List includes)
    {
        _includes= includes;
    }
    
    public void setExcludes(List excludes)
    {
        _excludes = excludes;
    }
    
    public List getIncludes()
    {
        return _includes;
    }
    
    public List getExcludes()
    {
        return _excludes;
    }

}
