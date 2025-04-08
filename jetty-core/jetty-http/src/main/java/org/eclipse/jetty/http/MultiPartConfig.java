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

package org.eclipse.jetty.http;

import java.nio.file.Path;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.resource.ResourceFactory;

import static org.eclipse.jetty.http.ComplianceViolation.Listener.NOOP;

/**
 * The Configuration needed to parse multipart/form-data.
 * @see MultiPartFormData#getParts(Content.Source, Attributes, String, MultiPartConfig)
 * @see MultiPartFormData#onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)
 */
public class MultiPartConfig
{
    private static final int DEFAULT_MAX_PARTS = 100;
    private static final int DEFAULT_MAX_SIZE = 50 * 1024 * 1024;
    private static final int DEFAULT_MAX_PART_SIZE = 10 * 1024 * 1024;
    private static final int DEFAULT_MAX_MEMORY_PART_SIZE = 1024;
    private static final int DEFAULT_MAX_HEADERS_SIZE = 8 * 1024;
    private static final boolean DEFAULT_USE_FILES_FOR_PARTS_WITHOUT_FILE_NAME = true;

    public static class Builder
    {
        private Path _location;
        private Integer _maxParts;
        private Long _maxSize;
        private Long _maxPartSize;
        private Long _maxMemoryPartSize;
        private Integer _maxHeadersSize;
        private Boolean _useFilesForPartsWithoutFileName;
        private MultiPartCompliance _complianceMode;
        private ComplianceViolation.Listener _violationListener;

        public Builder()
        {
        }

        /**
         * @param location the directory where parts will be saved as files.
         */
        public Builder location(String location)
        {
            location(ResourceFactory.root().newResource(location).getPath());
            return this;
        }
        
        /**
         * @param location the directory where parts will be saved as files.
         */
        public Builder location(Path location)
        {
            _location = location;
            return this;
        }

        /**
         * @param maxParts the maximum number of parts that can be parsed from the multipart content, or -1 for unlimited.
         */
        public Builder maxParts(int maxParts)
        {
            _maxParts = maxParts;
            return this;
        }

        /**
         * @param maxSize the maximum size in bytes of the whole multipart content, or -1 for unlimited.
         */
        public Builder maxSize(long maxSize)
        {
            _maxSize = maxSize;
            return this;
        }

        /**
         * @param maxPartSize  the maximum part size in bytes, or -1 for unlimited.
         */
        public Builder maxPartSize(long maxPartSize)
        {
            _maxPartSize = maxPartSize;
            return this;
        }

        /**
         * <p>Sets the maximum size of a part in memory, after which it will be written as a file.</p>
         * <p>Use value {@code 0} to always write the part to disk.</p>
         * <p>Use value {@code -1} to never write the part to disk.</p>
         *
         * @param maxMemoryPartSize the maximum part size which can be held in memory.
         */
        public Builder maxMemoryPartSize(long maxMemoryPartSize)
        {
            _maxMemoryPartSize = maxMemoryPartSize;
            return this;
        }

        /**
         * @param maxHeadersSize the max length of a {@link MultiPart.Part} headers, in bytes, or -1 for unlimited length.
         */
        public Builder maxHeadersSize(int maxHeadersSize)
        {
            _maxHeadersSize = maxHeadersSize;
            return this;
        }

        /**
         * @param useFilesForPartsWithoutFileName whether parts without a fileName may be stored as files.
         */
        public Builder useFilesForPartsWithoutFileName(Boolean useFilesForPartsWithoutFileName)
        {
            _useFilesForPartsWithoutFileName = useFilesForPartsWithoutFileName;
            return this;
        }

        /**
         * @param complianceMode the compliance mode.
         */
        public Builder complianceMode(MultiPartCompliance complianceMode)
        {
            _complianceMode = complianceMode;
            return this;
        }

        /**
         * @param violationListener the compliance violation listener.
         */
        public Builder violationListener(ComplianceViolation.Listener violationListener)
        {
            _violationListener = violationListener;
            return this;
        }

        public MultiPartConfig build()
        {
            return new MultiPartConfig(_location,
                _maxParts == null ? DEFAULT_MAX_PARTS : _maxParts,
                _maxSize == null ? DEFAULT_MAX_SIZE : _maxSize,
                _maxPartSize == null ? DEFAULT_MAX_PART_SIZE : _maxPartSize,
                _maxMemoryPartSize == null ? DEFAULT_MAX_MEMORY_PART_SIZE : _maxMemoryPartSize,
                _maxHeadersSize == null ? DEFAULT_MAX_HEADERS_SIZE : _maxHeadersSize,
                _useFilesForPartsWithoutFileName == null ? DEFAULT_USE_FILES_FOR_PARTS_WITHOUT_FILE_NAME : _useFilesForPartsWithoutFileName,
                _complianceMode == null ? MultiPartCompliance.RFC7578 : _complianceMode,
                _violationListener == null ? NOOP : _violationListener);
        }
    }

    private final Path _location;
    private final long _maxMemoryPartSize;
    private final long _maxPartSize;
    private final long _maxSize;
    private final int _maxParts;
    private final int _maxHeadersSize;
    private final boolean _useFilesForPartsWithoutFileName;
    private final MultiPartCompliance _compliance;
    private final ComplianceViolation.Listener _listener;

    private MultiPartConfig(Path location, int maxParts, long maxSize, long maxPartSize, long maxMemoryPartSize,
                            int maxHeadersSize, boolean useFilesForPartsWithoutFileName,
                            MultiPartCompliance compliance, ComplianceViolation.Listener listener)
    {
        this._location = location;
        this._maxParts = maxParts;
        this._maxSize = maxSize;
        this._maxPartSize = maxPartSize;
        this._maxMemoryPartSize = maxMemoryPartSize;
        this._maxHeadersSize = maxHeadersSize;
        this._useFilesForPartsWithoutFileName = useFilesForPartsWithoutFileName;
        this._compliance = compliance;
        this._listener = listener;
    }

    public Path getLocation()
    {
        return _location;
    }

    public int getMaxParts()
    {
        return _maxParts;
    }

    public long getMaxSize()
    {
        return _maxSize;
    }

    public long getMaxPartSize()
    {
        return _maxPartSize;
    }

    public long getMaxMemoryPartSize()
    {
        return _maxMemoryPartSize;
    }

    public int getMaxHeadersSize()
    {
        return _maxHeadersSize;
    }

    public boolean isUseFilesForPartsWithoutFileName()
    {
        return _useFilesForPartsWithoutFileName;
    }

    public MultiPartCompliance getMultiPartCompliance()
    {
        return _compliance;
    }

    public ComplianceViolation.Listener getViolationListener()
    {
        return _listener;
    }
}
