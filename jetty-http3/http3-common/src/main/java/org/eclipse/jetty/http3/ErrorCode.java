package org.eclipse.jetty.http3;

public enum ErrorCode
{
    NO_ERROR(0x100),
    PROTOCOL_ERROR(0x101),
    INTERNAL_ERROR(0x102),
    STREAM_CREATION_ERROR(0x103),
    CLOSED_CRITICAL_STREAM_ERROR(0x104),
    FRAME_UNEXPECTED_ERROR(0x105),
    FRAME_ERROR(0x106),
    EXCESSIVE_LOAD_ERROR(0x107),
    ID_ERROR(0x108),
    SETTINGS_ERROR(0x109),
    MISSING_SETTINGS_ERROR(0x10A),
    REQUEST_REJECTED_ERROR(0x10B),
    REQUEST_CANCELLED_ERROR(0x10C),
    REQUEST_INCOMPLETE_ERROR(0x10D),
    HTTP_MESSAGE_ERROR(0x10E),
    HTTP_CONNECT_ERROR(0x10F),
    VERSION_FALLBACK_ERROR(0x110);

    private final int code;

    ErrorCode(int code)
    {
        this.code = code;
    }

    public int code()
    {
        return code;
    }
}
