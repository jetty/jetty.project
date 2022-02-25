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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SettingsBodyParser extends BodyParser
{
    private static final Logger LOG = LoggerFactory.getLogger(SettingsBodyParser.class);

    private final int maxKeys;
    private State state = State.PREPARE;
    private int cursor;
    private int length;
    private int settingId;
    private int settingValue;
    private int keys;
    private Map<Integer, Integer> settings;

    public SettingsBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        this(headerParser, listener, SettingsFrame.DEFAULT_MAX_KEYS);
    }

    public SettingsBodyParser(HeaderParser headerParser, Parser.Listener listener, int maxKeys)
    {
        super(headerParser, listener);
        this.maxKeys = maxKeys;
    }

    protected void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        length = 0;
        settingId = 0;
        settingValue = 0;
        settings = null;
    }

    public int getMaxKeys()
    {
        return maxKeys;
    }

    @Override
    protected void emptyBody(ByteBuffer buffer)
    {
        boolean isReply = hasFlag(Flags.ACK);
        SettingsFrame frame = new SettingsFrame(Collections.emptyMap(), isReply);
        if (!isReply && !rateControlOnEvent(frame))
            connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_settings_frame_rate");
        else
            onSettings(frame);
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        return parse(buffer, getStreamId(), getBodyLength());
    }

    private boolean parse(ByteBuffer buffer, int streamId, int bodyLength)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PREPARE:
                {
                    // SPEC: wrong streamId is treated as connection error.
                    if (streamId != 0)
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_settings_frame");
                    length = bodyLength;
                    settings = new HashMap<>();
                    state = State.SETTING_ID;
                    break;
                }
                case SETTING_ID:
                {
                    if (buffer.remaining() >= 2)
                    {
                        settingId = buffer.getShort() & 0xFF_FF;
                        state = State.SETTING_VALUE;
                        length -= 2;
                        if (length <= 0)
                            return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_settings_frame");
                    }
                    else
                    {
                        cursor = 2;
                        settingId = 0;
                        state = State.SETTING_ID_BYTES;
                    }
                    break;
                }
                case SETTING_ID_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    settingId += currByte << (8 * cursor);
                    --length;
                    if (length <= 0)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_settings_frame");
                    if (cursor == 0)
                    {
                        state = State.SETTING_VALUE;
                    }
                    break;
                }
                case SETTING_VALUE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        settingValue = buffer.getInt();
                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("setting %d=%d", settingId, settingValue));
                        if (!onSetting(buffer, settings, settingId, settingValue))
                            return false;
                        state = State.SETTING_ID;
                        length -= 4;
                        if (length == 0)
                            return onSettings(buffer, settings);
                    }
                    else
                    {
                        cursor = 4;
                        settingValue = 0;
                        state = State.SETTING_VALUE_BYTES;
                    }
                    break;
                }
                case SETTING_VALUE_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    settingValue += currByte << (8 * cursor);
                    --length;
                    if (cursor > 0 && length <= 0)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_settings_frame");
                    if (cursor == 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("setting %d=%d", settingId, settingValue));
                        if (!onSetting(buffer, settings, settingId, settingValue))
                            return false;
                        state = State.SETTING_ID;
                        if (length == 0)
                            return onSettings(buffer, settings);
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    protected boolean onSetting(ByteBuffer buffer, Map<Integer, Integer> settings, int key, int value)
    {
        ++keys;
        if (keys > getMaxKeys())
            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_settings_frame");
        settings.put(key, value);
        return true;
    }

    protected boolean onSettings(ByteBuffer buffer, Map<Integer, Integer> settings)
    {
        Integer enablePush = settings.get(SettingsFrame.ENABLE_PUSH);
        if (enablePush != null && enablePush != 0 && enablePush != 1)
            return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_settings_enable_push");

        Integer initialWindowSize = settings.get(SettingsFrame.INITIAL_WINDOW_SIZE);
        // Values greater than Integer.MAX_VALUE will overflow to negative.
        if (initialWindowSize != null && initialWindowSize < 0)
            return connectionFailure(buffer, ErrorCode.FLOW_CONTROL_ERROR.code, "invalid_settings_initial_window_size");

        Integer maxFrameLength = settings.get(SettingsFrame.MAX_FRAME_SIZE);
        if (maxFrameLength != null && (maxFrameLength < Frame.DEFAULT_MAX_LENGTH || maxFrameLength > Frame.MAX_MAX_LENGTH))
            return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_settings_max_frame_size");

        SettingsFrame frame = new SettingsFrame(settings, hasFlag(Flags.ACK));
        return onSettings(frame);
    }

    private boolean onSettings(SettingsFrame frame)
    {
        reset();
        notifySettings(frame);
        return true;
    }

    /**
     * <p>Parses the given buffer containing the whole body of a {@code SETTINGS} frame
     * (without header bytes), typically from the {@code HTTP2-Settings} header.</p>
     *
     * @param buffer the buffer containing the body of {@code SETTINGS} frame
     * @return the {@code SETTINGS} frame from the parsed body bytes
     */
    public static SettingsFrame parseBody(final ByteBuffer buffer)
    {
        AtomicReference<SettingsFrame> frameRef = new AtomicReference<>();
        SettingsBodyParser parser = new SettingsBodyParser(new HeaderParser(RateControl.NO_RATE_CONTROL), new Parser.Listener.Adapter()
        {
            @Override
            public void onSettings(SettingsFrame frame)
            {
                frameRef.set(frame);
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
                frameRef.set(null);
            }
        });
        if (buffer.hasRemaining())
            parser.parse(buffer, 0, buffer.remaining());
        else
            parser.emptyBody(buffer);
        return frameRef.get();
    }

    private enum State
    {
        PREPARE, SETTING_ID, SETTING_ID_BYTES, SETTING_VALUE, SETTING_VALUE_BYTES
    }
}
