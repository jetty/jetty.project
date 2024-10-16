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

package org.eclipse.jetty.compression.brotli;

import com.aayushatharva.brotli4j.encoder.Encoder;
import com.aayushatharva.brotli4j.encoder.Encoder.Parameters;
import org.eclipse.jetty.compression.EncoderConfig;

public class BrotliEncoderConfig implements EncoderConfig
{
    /**
     * Default Brotli Quality (Compression Level).
     *
     * @see <a href="https://www.brotli.org/encode.html">Encoder Defaults</a>
     */
    public static final int DEFAULT_QUALITY = 11;
    /**
     * Default Brotli Mode (Strategy).
     *
     * @see <a href="https://www.brotli.org/encode.html">Encoder Defaults</a>
     */
    public static final Encoder.Mode DEFAULT_MODE = Encoder.Mode.GENERIC;
    /**
     * Default Brotli Window.
     *
     * @see <a href="https://www.brotli.org/encode.html">Encoder Defaults</a>
     */
    public static final int DEFAULT_WINDOW = 22;

    public static final int MIN_BUFFER_SIZE = 32;

    private int bufferSize = 4096;
    private int quality = DEFAULT_QUALITY;
    private Encoder.Mode mode = DEFAULT_MODE;
    private int lgWindow = DEFAULT_WINDOW;

    public Parameters asEncoderParams()
    {
        Parameters params = new Parameters();
        params.setQuality(getCompressionLevel());
        params.setMode(getMode());
        params.setWindow(getLgWindow());
        return params;
    }

    @Override
    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Set Encoder output buffer size.
     *
     * @param size size of output buffer.
     */
    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = Math.max(MIN_BUFFER_SIZE, size);
    }

    /**
     * Get Brotli encoder compression quality.
     *
     * @return the compression quality.
     */
    @Override
    public int getCompressionLevel()
    {
        return quality;
    }

    /**
     * Brotli encoder compression quality.
     *
     * @param level the level to set (in range 0 to 11)
     */
    @Override
    public void setCompressionLevel(int level)
    {
        // range of valid values
        if (level < 0 || level > 11)
        {
            throw new IllegalArgumentException("Compression Level should be in range [0, 11]");
        }
        this.quality = level;
    }

    /**
     * The Brotli log2(LZ window size).
     *
     * @return the lgWindow size.
     */
    public int getLgWindow()
    {
        return lgWindow;
    }

    /**
     * Set the Brotli log2(LZ window size).
     *
     * @param window the window size (in range 10 to 24)
     */
    public void setLgWindow(int window)
    {
        if ((window < 10) || (window > 24))
        {
            throw new IllegalArgumentException("LG Window Size should be in range [10, 24]");
        }
        this.lgWindow = window;
    }

    public Encoder.Mode getMode()
    {
        return mode;
    }

    @Override
    public int getStrategy()
    {
        return mode.ordinal();
    }

    /**
     * Brotli mode.
     *
     * <ul>
     *   <li>{@code 0} - is equivalent to {@code BROTLI_MODE_GENERIC}</li>
     *   <li>{@code 1} - is equivalent to {@code BROTLI_MODE_TEXT}</li>
     *   <li>{@code 2} - is equivalent to {@code BROTLI_MODE_FONT}</li>
     * </ul>
     *
     * @param strategy the strategy to use.
     * @see <a href="https://www.brotli.org/encode.html#aa6f">Brotli Mode</a>
     */
    @Override
    public void setStrategy(int strategy)
    {
        if ((strategy < 0) || (strategy > Encoder.Mode.values().length))
            throw new IllegalArgumentException("Unsupported brotli strategy mode: " + strategy);

        mode = Encoder.Mode.of(strategy);
    }
}
