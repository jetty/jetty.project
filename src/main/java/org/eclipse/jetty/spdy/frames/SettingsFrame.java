package org.eclipse.jetty.spdy.frames;

import java.util.Map;

import org.eclipse.jetty.spdy.api.SettingsInfo;

public class SettingsFrame extends ControlFrame
{
    private final Map<SettingsInfo.Key, Integer> settings;

    public SettingsFrame(short version, byte flags, Map<SettingsInfo.Key, Integer> settings)
    {
        super(version, ControlFrameType.SETTINGS, flags);
        this.settings = settings;
    }

    public boolean isClearPersisted()
    {
        return (getFlags() & SettingsInfo.CLEAR_PERSISTED) == SettingsInfo.CLEAR_PERSISTED;
    }

    public Map<SettingsInfo.Key, Integer> getSettings()
    {
        return settings;
    }

    @Override
    public String toString()
    {
        return super.toString() + " clear_persisted=" + isClearPersisted() + " settings=" + getSettings();
    }
}
