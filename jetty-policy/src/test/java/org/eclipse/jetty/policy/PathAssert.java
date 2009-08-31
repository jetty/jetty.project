// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.policy;

import java.io.File;

import junit.framework.Assert;

public class PathAssert
{
    public static void assertDirExists(String msg, File path)
    {
        assertExists(msg,path);
        Assert.assertTrue(msg + " path should be a Dir : " + path.getAbsolutePath(),path.isDirectory());
    }

    public static void assertFileExists(String msg, File path)
    {
        assertExists(msg,path);
        Assert.assertTrue(msg + " path should be a File : " + path.getAbsolutePath(),path.isFile());
    }

    public static void assertExists(String msg, File path)
    {
        Assert.assertTrue(msg + " path should exist: " + path.getAbsolutePath(),path.exists());
    }
}
