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

package org.eclipse.jetty.client;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import org.eclipse.jetty.client.Response.CompleteListener;

/**
 * All classes should have a javadoc
 */
public class PathResponseListener implements CompleteListener, Response.ContentListener
{
    private Path path;
    private File file;
    private FileOutputStream output;

    public PathResponseListener(Path path)
    {
        this.path = path;
    }

    @Override
    public void onContent(Response response, ByteBuffer content) throws Exception
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onComplete(Result result)
    {
        // TODO Auto-generated method stub

    }

}
