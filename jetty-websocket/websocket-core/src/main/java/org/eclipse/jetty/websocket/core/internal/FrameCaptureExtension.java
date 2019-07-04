//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.internal;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public class FrameCaptureExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(FrameCaptureExtension.class);

    private static final int BUFSIZE = 32768;
    private Generator generator;
    private Path outputDir;
    private String prefix = "frame";
    private Path incomingFramesPath;
    private Path outgoingFramesPath;

    private AtomicInteger incomingCount = new AtomicInteger(0);
    private AtomicInteger outgoingCount = new AtomicInteger(0);

    private SeekableByteChannel incomingChannel;
    private SeekableByteChannel outgoingChannel;

    @Override
    public String getName()
    {
        return "@frame-capture";
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        saveFrame(frame, false);
        try
        {
            nextIncomingFrame(frame, callback);
        }
        catch (Throwable t)
        {
            IO.close(incomingChannel);
            incomingChannel = null;
            throw t;
        }
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        saveFrame(frame, true);
        try
        {
            nextOutgoingFrame(frame, callback, batch);
        }
        catch (Throwable t)
        {
            IO.close(outgoingChannel);
            outgoingChannel = null;
            throw t;
        }
    }

    private void saveFrame(Frame frame, boolean outgoing)
    {
        if (outputDir == null || generator == null)
        {
            return;
        }

        @SuppressWarnings("resource")
        SeekableByteChannel channel = (outgoing) ? outgoingChannel : incomingChannel;

        if (channel == null)
        {
            return;
        }

        ByteBuffer buf = getBufferPool().acquire(BUFSIZE, false);
        BufferUtil.flipToFill(buf);

        try
        {
            Frame f = Frame.copy(frame);
            f.setMask(null); // TODO is this needed?
            generator.generateHeaderBytes(f, buf);
            BufferUtil.flipToFlush(buf, 0);
            channel.write(buf);
            if (frame.hasPayload())
            {
                channel.write(frame.getPayload().slice());
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Saved {} frame #{}", (outgoing) ? "outgoing" : "incoming",
                    (outgoing) ? outgoingCount.incrementAndGet() : incomingCount.incrementAndGet());
        }
        catch (IOException e)
        {
            LOG.warn("Unable to save frame: " + frame, e);
        }
        finally
        {
            getBufferPool().release(buf);
        }
    }

    @Override
    public void init(ExtensionConfig config, WebSocketComponents components)
    {
        super.init(config, components);

        String cfgOutputDir = config.getParameter("output-dir", null);
        if (StringUtil.isNotBlank(cfgOutputDir))
        {
            Path path = new File(cfgOutputDir).toPath();
            if (Files.isDirectory(path) && Files.exists(path) && Files.isWritable(path))
            {
                this.outputDir = path;
            }
            else
            {
                LOG.warn("Unable to configure {}: not a valid output directory", path.toAbsolutePath().toString());
            }
        }

        String cfgPrefix = config.getParameter("prefix", "frame");
        if (StringUtil.isNotBlank(cfgPrefix))
        {
            this.prefix = cfgPrefix;
        }

        if (this.outputDir != null)
        {
            try
            {
                Path dir = this.outputDir.toRealPath();

                // create a non-validating, read-only generator
                String tstamp = String.format("%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS", Calendar.getInstance());
                incomingFramesPath = dir.resolve(String.format("%s-%s-incoming.dat", this.prefix, tstamp));
                outgoingFramesPath = dir.resolve(String.format("%s-%s-outgoing.dat", this.prefix, tstamp));

                incomingChannel = Files.newByteChannel(incomingFramesPath, CREATE, WRITE);
                outgoingChannel = Files.newByteChannel(outgoingFramesPath, CREATE, WRITE);

                this.generator = new Generator(getBufferPool(), true);
            }
            catch (IOException e)
            {
                LOG.warn("Unable to create capture file(s)", e);
            }
        }
    }
}
