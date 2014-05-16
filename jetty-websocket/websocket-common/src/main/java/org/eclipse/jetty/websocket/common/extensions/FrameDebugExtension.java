//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Generator;

public class FrameDebugExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(FrameDebugExtension.class);

    private static final int BUFSIZE = 32768;
    private Generator generator;
    private Path outputDir;
    private String prefix = "frame";
    private AtomicLong incomingId = new AtomicLong(0);
    private AtomicLong outgoingId = new AtomicLong(0);

    @Override
    public String getName()
    {
        return "@frame-debug";
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        saveFrame(frame,false);
        nextIncomingFrame(frame);
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        saveFrame(frame,true);
        nextOutgoingFrame(frame,callback,batchMode);
    }

    private void saveFrame(Frame frame, boolean outgoing)
    {
        if (outputDir == null || generator == null)
        {
            return;
        }

        StringBuilder filename = new StringBuilder();
        filename.append(prefix);
        if (outgoing)
        {
            filename.append(String.format("-outgoing-%05d",outgoingId.getAndIncrement()));
        }
        else
        {
            filename.append(String.format("-incoming-%05d",incomingId.getAndIncrement()));
        }
        filename.append(".dat");

        Path outputFile = outputDir.resolve(filename.toString());
        ByteBuffer buf = getBufferPool().acquire(BUFSIZE,false);
        try (SeekableByteChannel channel = Files.newByteChannel(outputFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE))
        {
            generator.generateHeaderBytes(frame,buf);
            channel.write(buf);
            if (frame.hasPayload())
            {
                channel.write(frame.getPayload().slice());
            }
            LOG.debug("Saved raw frame: {}",outputFile.toString());
        }
        catch (IOException e)
        {
            LOG.warn("Unable to save frame: " + filename.toString(),e);
        }
        finally
        {
            getBufferPool().release(buf);
        }
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        String cfgOutputDir = config.getParameter("output-dir",null);
        if (StringUtil.isNotBlank(cfgOutputDir))
        {
            Path path = new File(cfgOutputDir).toPath();
            if (Files.isDirectory(path) && Files.exists(path) && Files.isWritable(path))
            {
                this.outputDir = path;
            }
            else
            {
                LOG.warn("Unable to configure {}: not a valid output directory",path.toAbsolutePath().toString());
            }
        }

        String cfgPrefix = config.getParameter("prefix","frame");
        if (StringUtil.isNotBlank(cfgPrefix))
        {
            this.prefix = cfgPrefix;
        }

        if (this.outputDir != null)
        {
            // create a non-validating, read-only generator
            this.generator = new Generator(getPolicy(),getBufferPool(),false,true);
        }
    }
}
