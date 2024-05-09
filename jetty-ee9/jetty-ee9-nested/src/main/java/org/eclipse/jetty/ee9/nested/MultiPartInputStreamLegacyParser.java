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

package org.eclipse.jetty.ee9.nested;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MultiPartInputStreamLegacyParser.
 *
 * <p>
 * Handle a MultiPart Mime input stream, breaking it up on the boundary into files and strings.
 * </p>
 *
 * <p>
 * Non Compliance warnings are documented by the method {@link #getNonComplianceWarnings()}
 * </p>
 *
 * @deprecated Replaced by {@link MultiPartFormInputStream}.
 * This code is slower and subject to more bugs than its replacement {@link MultiPartFormInputStream}. However,
 * this class accepts formats non-compliant the RFC that the new {@link MultiPartFormInputStream} does not accept.
 */
@Deprecated
class MultiPartInputStreamLegacyParser implements MultiPart.Parser
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiPartInputStreamLegacyParser.class);
    public static final MultipartConfigElement __DEFAULT_MULTIPART_CONFIG =
        new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
    public static final MultiMap<Part> EMPTY_MAP = new MultiMap<>(Collections.emptyMap());
    private final int _maxParts;
    private int _numParts;
    protected InputStream _in;
    protected MultipartConfigElement _config;
    protected String _contentType;
    protected MultiMap<Part> _parts;
    protected Exception _err;
    protected File _tmpDir;
    protected File _contextTmpDir;
    protected boolean _writeFilesWithFilenames;
    protected boolean _parsed;

    private final MultiPartCompliance _multiPartCompliance;
    private final List<ComplianceViolation.Event> nonComplianceWarnings = new ArrayList<>();

    /**
     * @return an EnumSet of non compliances with the RFC that were accepted by this parser
     */
    @Override
    public List<ComplianceViolation.Event> getNonComplianceWarnings()
    {
        return nonComplianceWarnings;
    }

    public class MultiPart implements Part
    {
        protected String _name;
        protected String _filename;
        protected File _file;
        protected OutputStream _out;
        protected ByteArrayOutputStream2 _bout;
        protected String _contentType;
        protected MultiMap<String> _headers;
        protected long _size = 0;
        protected boolean _temporary = true;

        public MultiPart(String name, String filename) throws IOException
        {
            _name = name;
            _filename = filename;
        }

        @Override
        public String toString()
        {
            return String.format(
                "Part{n=%s,fn=%s,ct=%s,s=%d,t=%b,f=%s}", _name, _filename, _contentType, _size, _temporary, _file);
        }

        protected void setContentType(String contentType)
        {
            _contentType = contentType;
        }

        protected void open() throws IOException
        {
            // We will either be writing to a file, if it has a filename on the content-disposition
            // and otherwise a byte-array-input-stream, OR if we exceed the getFileSizeThreshold, we
            // will need to change to write to a file.
            if (isWriteFilesWithFilenames() && _filename != null && _filename.trim().length() > 0)
            {
                createFile();
            }
            else
            {
                // Write to a buffer in memory until we discover we've exceeded the
                // MultipartConfig fileSizeThreshold
                _out = _bout = new ByteArrayOutputStream2();
            }
        }

        protected void close() throws IOException
        {
            _out.close();
        }

        protected void write(int b) throws IOException
        {
            if (MultiPartInputStreamLegacyParser.this._config.getMaxFileSize() > 0 && _size + 1 > MultiPartInputStreamLegacyParser.this._config.getMaxFileSize())
                throw new IllegalStateException("Multipart Mime part " + _name + " exceeds max filesize");

            if (MultiPartInputStreamLegacyParser.this._config.getFileSizeThreshold() > 0 && _size + 1 > MultiPartInputStreamLegacyParser.this._config.getFileSizeThreshold() && _file == null)
                createFile();

            _out.write(b);
            _size++;
        }

        protected void write(byte[] bytes, int offset, int length) throws IOException
        {
            if (MultiPartInputStreamLegacyParser.this._config.getMaxFileSize() > 0 && _size + length > MultiPartInputStreamLegacyParser.this._config.getMaxFileSize())
                throw new IllegalStateException("Multipart Mime part " + _name + " exceeds max filesize");

            if (MultiPartInputStreamLegacyParser.this._config.getFileSizeThreshold() > 0 && _size + length > MultiPartInputStreamLegacyParser.this._config.getFileSizeThreshold() && _file == null)
                createFile();

            _out.write(bytes, offset, length);
            _size += length;
        }

        protected void createFile() throws IOException
        {
            Path parent = MultiPartInputStreamLegacyParser.this._tmpDir.toPath();
            Path tempFile = Files.createTempFile(parent, "MultiPart", "");
            _file = tempFile.toFile();

            OutputStream fos = Files.newOutputStream(tempFile, StandardOpenOption.WRITE);
            BufferedOutputStream bos = new BufferedOutputStream(fos);

            if (_size > 0 && _out != null)
            {
                // already written some bytes, so need to copy them into the file
                _out.flush();
                _bout.writeTo(bos);
                _out.close();
            }
            _bout = null;
            _out = bos;
        }

        protected void setHeaders(MultiMap<String> headers)
        {
            _headers = headers;
        }

        /**
         * @see Part#getContentType()
         */
        @Override
        public String getContentType()
        {
            return _contentType;
        }

        /**
         * @see Part#getHeader(String)
         */
        @Override
        public String getHeader(String name)
        {
            if (name == null)
                return null;
            return _headers.getValue(name.toLowerCase(Locale.ENGLISH), 0);
        }

        /**
         * @see Part#getHeaderNames()
         */
        @Override
        public Collection<String> getHeaderNames()
        {
            return _headers.keySet();
        }

        /**
         * @see Part#getHeaders(String)
         */
        @Override
        public Collection<String> getHeaders(String name)
        {
            return _headers.getValues(name);
        }

        /**
         * @see Part#getInputStream()
         */
        @Override
        public InputStream getInputStream() throws IOException
        {
            if (_file != null)
            {
                // written to a file, whether temporary or not
                return new BufferedInputStream(new FileInputStream(_file));
            }
            else
            {
                // part content is in memory
                return new ByteArrayInputStream(_bout.getBuf(), 0, _bout.size());
            }
        }

        /**
         * @see Part#getSubmittedFileName()
         */
        @Override
        public String getSubmittedFileName()
        {
            return getContentDispositionFilename();
        }

        public byte[] getBytes()
        {
            if (_bout != null)
                return _bout.toByteArray();
            return null;
        }

        /**
         * @see Part#getName()
         */
        @Override
        public String getName()
        {
            return _name;
        }

        /**
         * @see Part#getSize()
         */
        @Override
        public long getSize()
        {
            return _size;
        }

        /**
         * @see Part#write(String)
         */
        @Override
        public void write(String fileName) throws IOException
        {
            if (_file == null)
            {
                _temporary = false;

                // part data is only in the ByteArrayOutputStream and never been written to disk
                _file = new File(_tmpDir, fileName);

                BufferedOutputStream bos = null;
                try
                {
                    bos = new BufferedOutputStream(new FileOutputStream(_file));
                    _bout.writeTo(bos);
                    bos.flush();
                }
                finally
                {
                    if (bos != null)
                        bos.close();
                    _bout = null;
                }
            }
            else
            {
                // the part data is already written to a temporary file, just rename it
                _temporary = false;

                Path src = _file.toPath();
                Path target = src.resolveSibling(fileName);
                Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
                _file = target.toFile();
            }
        }

        /**
         * Remove the file, whether or not Part.write() was called on it
         * (ie no longer temporary)
         *
         * @see Part#delete()
         */
        @Override
        public void delete() throws IOException
        {
            if (_file != null && _file.exists())
                _file.delete();
        }

        /**
         * Only remove tmp files.
         *
         * @throws IOException if unable to delete the file
         */
        public void cleanUp() throws IOException
        {
            if (_temporary && _file != null && _file.exists())
                _file.delete();
        }

        /**
         * Get the file
         *
         * @return the file, if any, the data has been written to.
         */
        public File getFile()
        {
            return _file;
        }

        /**
         * Get the filename from the content-disposition.
         *
         * @return null or the filename
         */
        public String getContentDispositionFilename()
        {
            return _filename;
        }
    }

    /**
     * @param in Request input stream
     * @param contentType Content-Type header
     * @param config MultipartConfigElement
     * @param contextTmpDir javax.servlet.context.tempdir
     * @param maxParts the maximum number of parts that can be parsed from the multipart content (0 for no parts allowed, -1 for unlimited parts).
     */
    public MultiPartInputStreamLegacyParser(
                                            MultiPartCompliance multiPartCompliance,
                                            InputStream in,
                                            String contentType,
                                            MultipartConfigElement config,
                                            File contextTmpDir,
                                            int maxParts)
    {
        _multiPartCompliance = multiPartCompliance;
        _contentType = contentType;
        _config = config;
        _contextTmpDir = contextTmpDir;
        _maxParts = maxParts;
        if (_contextTmpDir == null)
            _contextTmpDir = new File(System.getProperty("java.io.tmpdir"));

        if (_config == null)
            _config = new MultipartConfigElement(_contextTmpDir.getAbsolutePath());

        if (in instanceof ServletInputStream)
        {
            if (((ServletInputStream)in).isFinished())
            {
                _parts = EMPTY_MAP;
                _parsed = true;
                return;
            }
        }
        _in = new ReadLineInputStream(in);
    }

    /**
     * Get the already parsed parts.
     *
     * @return the parts that were parsed
     */
    public Collection<Part> getParsedParts()
    {
        if (_parts == null)
            return Collections.emptyList();

        Collection<List<Part>> values = _parts.values();
        List<Part> parts = new ArrayList<>();
        for (List<Part> o : values)
        {
            List<Part> asList = LazyList.getList(o, false);
            parts.addAll(asList);
        }
        return parts;
    }

    /**
     * Delete any tmp storage for parts, and clear out the parts list.
     */
    @Override
    public void deleteParts()
    {
        if (!_parsed)
            return;

        Throwable err = null;
        Collection<Part> parts = getParsedParts();
        for (Part p : parts)
        {
            try
            {
                ((MultiPart)p).cleanUp();
            }
            catch (Exception e)
            {
                err = ExceptionUtil.combine(err, e);
            }
        }
        _parts.clear();
        ExceptionUtil.ifExceptionThrowUnchecked(err);
    }

    /**
     * Parse, if necessary, the multipart data and return the list of Parts.
     *
     * @return the parts
     * @throws IOException if unable to get the parts
     */
    @Override
    public Collection<Part> getParts() throws IOException
    {
        if (!_parsed)
            parse();
        throwIfError();

        Collection<List<Part>> values = _parts.values();
        List<Part> parts = new ArrayList<>();
        for (List<Part> o : values)
        {
            List<Part> asList = LazyList.getList(o, false);
            parts.addAll(asList);
        }
        return parts;
    }

    /**
     * Get the named Part.
     *
     * @param name the part name
     * @return the parts
     * @throws IOException if unable to get the part
     */
    @Override
    public Part getPart(String name) throws IOException
    {
        if (!_parsed)
            parse();
        throwIfError();
        return _parts.getValue(name, 0);
    }

    /**
     * Throws an exception if one has been latched.
     *
     * @throws IOException the exception (if present)
     */
    protected void throwIfError() throws IOException
    {
        if (_err != null)
        {
            if (_err instanceof IOException)
                throw (IOException)_err;
            if (_err instanceof IllegalStateException)
                throw (IllegalStateException)_err;
            throw new IllegalStateException(_err);
        }
    }

    /**
     * Parse, if necessary, the multipart stream.
     */
    protected void parse()
    {
        // have we already parsed the input?
        if (_parsed)
            return;
        _parsed = true;

        // initialize
        long total = 0; // keep running total of size of bytes read from input and throw an exception if exceeds
        // MultipartConfigElement._maxRequestSize
        _parts = new MultiMap<>();

        // if its not a multipart request, don't parse it
        if (_contentType == null || !_contentType.startsWith("multipart/form-data"))
            return;

        try
        {
            // sort out the location to which to write the files

            if (_config.getLocation() == null)
                _tmpDir = _contextTmpDir;
            else if ("".equals(_config.getLocation()))
                _tmpDir = _contextTmpDir;
            else
            {
                File f = new File(_config.getLocation());
                if (f.isAbsolute())
                    _tmpDir = f;
                else
                    _tmpDir = new File(_contextTmpDir, _config.getLocation());
            }

            if (!_tmpDir.exists())
                _tmpDir.mkdirs();

            String contentTypeBoundary = "";
            int bstart = _contentType.indexOf("boundary=");
            if (bstart >= 0)
            {
                int bend = _contentType.indexOf(";", bstart);
                bend = (bend < 0 ? _contentType.length() : bend);
                contentTypeBoundary =
                    unquote(value(_contentType.substring(bstart, bend)).trim());
            }

            String boundary = "--" + contentTypeBoundary;
            String lastBoundary = boundary + "--";
            byte[] byteBoundary = lastBoundary.getBytes(StandardCharsets.ISO_8859_1);

            // Get first boundary
            String line = null;
            try
            {
                line = ((ReadLineInputStream)_in).readLine();
            }
            catch (IOException e)
            {
                LOG.warn("Badly formatted multipart request");
                throw e;
            }

            if (line == null)
                throw new IOException("Missing content for multipart request");

            boolean badFormatLogged = false;

            String untrimmed = line;
            line = line.trim();
            while (line != null && !line.equals(boundary) && !line.equals(lastBoundary))
            {
                if (!badFormatLogged)
                {
                    LOG.warn("Badly formatted multipart request");
                    badFormatLogged = true;
                }
                line = ((ReadLineInputStream)_in).readLine();
                untrimmed = line;
                if (line != null)
                    line = line.trim();
            }

            if (line == null || line.length() == 0)
                throw new IOException("Missing initial multi part boundary");

            // Empty multipart.
            if (line.equals(lastBoundary))
                return;

            // check compliance of preamble
            // this will show up as whitespace before the boundary that exists after the preamble
            if (Character.isWhitespace(untrimmed.charAt(0)))
                nonComplianceWarnings.add(new ComplianceViolation.Event(
                    MultiPartCompliance.LEGACY,
                    MultiPartCompliance.Violation.WHITESPACE_BEFORE_BOUNDARY,
                    String.format("0x%02x", untrimmed.charAt(0))));

            // Read each part
            boolean lastPart = false;

            outer:
            while (!lastPart)
            {
                String contentDisposition = null;
                String contentType = null;
                String contentTransferEncoding = null;

                MultiMap<String> headers = new MultiMap<>();
                while (true)
                {
                    line = ((ReadLineInputStream)_in).readLine();

                    // No more input
                    if (line == null)
                        break outer;

                    // end of headers:
                    if ("".equals(line))
                        break;

                    total += line.length();
                    if (_config.getMaxRequestSize() > 0 && total > _config.getMaxRequestSize())
                        throw new IllegalStateException(
                            "Request exceeds maxRequestSize (" + _config.getMaxRequestSize() + ")");

                    // get content-disposition and content-type
                    int c = line.indexOf(':');
                    if (c > 0)
                    {
                        String key = line.substring(0, c).trim().toLowerCase(Locale.ENGLISH);
                        String value = line.substring(c + 1).trim();
                        headers.put(key, value);
                        if (key.equalsIgnoreCase("content-disposition"))
                            contentDisposition = value;
                        if (key.equalsIgnoreCase("content-type"))
                            contentType = value;
                        if (key.equals("content-transfer-encoding"))
                            contentTransferEncoding = value;
                    }
                }

                // Extract content-disposition
                boolean formData = false;
                if (contentDisposition == null)
                {
                    throw new IOException("Missing content-disposition");
                }

                QuotedStringTokenizer tok = QuotedStringTokenizer.builder()
                    .legacy()
                    .delimiters(";")
                    .returnQuotes()
                    .build();
                String name = null;
                String filename = null;
                Iterator<String> itok = tok.tokenize(contentDisposition);
                while (itok.hasNext())
                {
                    String t = itok.next().trim();
                    String tl = t.toLowerCase(Locale.ENGLISH);
                    if (tl.startsWith("form-data"))
                        formData = true;
                    else if (tl.startsWith("name="))
                        name = value(t);
                    else if (tl.startsWith("filename="))
                        filename = filenameValue(t);
                }

                // Check disposition
                if (!formData)
                {
                    continue;
                }
                // It is valid for reset and submit buttons to have an empty name.
                // If no name is supplied, the browser skips sending the info for that field.
                // However, if you supply the empty string as the name, the browser sends the
                // field, with name as the empty string. So, only continue this loop if we
                // have not yet seen a name field.
                if (name == null)
                {
                    continue;
                }

                // Check if we can create a new part.
                _numParts++;
                if (_maxParts >= 0 && _numParts > _maxParts)
                    throw new IllegalStateException(
                        String.format("Form with too many parts [%d > %d]", _numParts, _maxParts));

                // Have a new Part
                MultiPart part = new MultiPart(name, filename);
                part.setHeaders(headers);
                part.setContentType(contentType);
                _parts.add(name, part);
                part.open();

                InputStream partInput = null;
                if ("base64".equalsIgnoreCase(contentTransferEncoding))
                {
                    nonComplianceWarnings.add(new ComplianceViolation.Event(
                        MultiPartCompliance.LEGACY,
                        MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING,
                        contentTransferEncoding));
                    if (_multiPartCompliance.allows(MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING))
                        partInput = new Base64InputStream((ReadLineInputStream)_in);
                    else
                        partInput = _in;
                }
                else if ("quoted-printable".equalsIgnoreCase(contentTransferEncoding))
                {
                    nonComplianceWarnings.add(new ComplianceViolation.Event(
                        MultiPartCompliance.LEGACY,
                        MultiPartCompliance.Violation.QUOTED_PRINTABLE_TRANSFER_ENCODING,
                        contentTransferEncoding));
                    partInput = new FilterInputStream(_in)
                    {
                        @Override
                        public int read() throws IOException
                        {
                            int c = in.read();
                            if (c >= 0 && c == '=')
                            {
                                int hi = in.read();
                                int lo = in.read();
                                if (hi < 0 || lo < 0)
                                {
                                    throw new IOException("Unexpected end to quoted-printable byte");
                                }
                                char[] chars = new char[]{(char)hi, (char)lo};
                                c = Integer.parseInt(new String(chars), 16);
                            }
                            return c;
                        }
                    };
                }
                else
                    partInput = _in;

                try
                {
                    int state = -2;
                    int c;
                    boolean cr = false;
                    boolean lf = false;

                    // loop for all lines
                    while (true)
                    {
                        int b = 0;
                        while ((c = (state != -2) ? state : partInput.read()) != -1)
                        {
                            total++;
                            if (_config.getMaxRequestSize() > 0 && total > _config.getMaxRequestSize())
                                throw new IllegalStateException(
                                    "Request exceeds maxRequestSize (" + _config.getMaxRequestSize() + ")");

                            state = -2;

                            // look for CR and/or LF
                            if (c == 13 || c == 10)
                            {
                                if (c == 13)
                                {
                                    partInput.mark(1);
                                    int tmp = partInput.read();
                                    if (tmp != 10)
                                        partInput.reset();
                                    else
                                        state = tmp;
                                }
                                break;
                            }

                            // Look for boundary
                            if (b >= 0 && b < byteBoundary.length && c == byteBoundary[b])
                            {
                                b++;
                            }
                            else
                            {
                                // Got a character not part of the boundary, so we don't have the boundary marker.
                                // Write out as many chars as we matched, then the char we're looking at.
                                if (cr)
                                    part.write(13);

                                if (lf)
                                    part.write(10);

                                cr = lf = false;
                                if (b > 0)
                                    part.write(byteBoundary, 0, b);

                                b = -1;
                                part.write(c);
                            }
                        }

                        // Check for incomplete boundary match, writing out the chars we matched along the way
                        if ((b > 0 && b < byteBoundary.length - 2) || (b == byteBoundary.length - 1))
                        {
                            if (cr)
                                part.write(13);

                            if (lf)
                                part.write(10);

                            cr = lf = false;
                            part.write(byteBoundary, 0, b);
                            b = -1;
                        }

                        // Boundary match. If we've run out of input or we matched the entire final boundary marker,
                        // then this is the last part.
                        if (b > 0 || c == -1)
                        {
                            if (b == byteBoundary.length)
                                lastPart = true;
                            if (state == 10)
                                state = -2;
                            break;
                        }

                        // handle CR LF
                        if (cr)
                            part.write(13);

                        if (lf)
                            part.write(10);

                        cr = (c == 13);
                        lf = (c == 10 || state == 10);
                        if (state == 10)
                            state = -2;
                    }
                }
                finally
                {
                    part.close();
                }
            }
            if (lastPart)
            {
                while (line != null)
                {
                    line = ((ReadLineInputStream)_in).readLine();
                }

                EnumSet<ReadLineInputStream.Termination> term = ((ReadLineInputStream)_in).getLineTerminations();

                if (term.contains(ReadLineInputStream.Termination.CR))
                    nonComplianceWarnings.add(new ComplianceViolation.Event(
                        MultiPartCompliance.LEGACY, MultiPartCompliance.Violation.CR_LINE_TERMINATION, "0x13"));
                if (term.contains(ReadLineInputStream.Termination.LF))
                    nonComplianceWarnings.add(new ComplianceViolation.Event(
                        MultiPartCompliance.LEGACY, MultiPartCompliance.Violation.LF_LINE_TERMINATION, "0x10"));
            }
            else
                throw new IOException("Incomplete parts");
        }
        catch (Exception e)
        {
            _err = e;
        }
    }

    /**
     * @deprecated no replacement offered.
     */
    @Deprecated
    public void setDeleteOnExit(boolean deleteOnExit)
    {
        // does nothing
    }

    public void setWriteFilesWithFilenames(boolean writeFilesWithFilenames)
    {
        _writeFilesWithFilenames = writeFilesWithFilenames;
    }

    public boolean isWriteFilesWithFilenames()
    {
        return _writeFilesWithFilenames;
    }

    /**
     * @deprecated no replacement offered.
     */
    @Deprecated
    public boolean isDeleteOnExit()
    {
        return false;
    }

    private String value(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx + 1).trim();
        return unquoteOnly(value);
    }

    private String filenameValue(String nameEqualsValue)
    {
        int idx = nameEqualsValue.indexOf('=');
        String value = nameEqualsValue.substring(idx + 1).trim();

        if (value.matches(".??[a-z,A-Z]\\:\\\\[^\\\\].*"))
        {
            // incorrectly escaped IE filenames that have the whole path
            // we just strip any leading & trailing quotes and leave it as is
            char first = value.charAt(0);
            if (first == '"' || first == '\'')
                value = value.substring(1);
            char last = value.charAt(value.length() - 1);
            if (last == '"' || last == '\'')
                value = value.substring(0, value.length() - 1);

            return value;
        }
        else
            // unquote the string, but allow any backslashes that don't
            // form a valid escape sequence to remain as many browsers
            // even on *nix systems will not escape a filename containing
            // backslashes
            return unquoteOnly(value, true);
    }

    // TODO: consider switching to Base64.getMimeDecoder().wrap(InputStream)
    private static class Base64InputStream extends InputStream
    {
        private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
        ReadLineInputStream _in;
        String _line;
        byte[] _buffer;
        int _pos;
        int _marklimit;
        int _markpos;
        Base64.Decoder base64Decoder = Base64.getMimeDecoder();

        public Base64InputStream(ReadLineInputStream rlis)
        {
            _in = rlis;
        }

        @Override
        public int read() throws IOException
        {
            if (_buffer == null || _pos >= _buffer.length)
            {
                _markpos = 0;
                _line = _in.readLine();
                if (_line == null)
                    return -1; // nothing left
                if (_line.startsWith("--"))
                    _buffer =
                        ("\r\n" + _line + "\r\n").getBytes(StandardCharsets.UTF_8); // boundary marking end of part
                else if (_line.isEmpty())
                    _buffer = CRLF; // blank line
                else
                {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream((4 * _line.length() / 3) + 2);
                    baos.write(base64Decoder.decode(_line));
                    _buffer = baos.toByteArray();
                }

                _pos = 0;
            }

            return _buffer[_pos++] & 0xFF;
        }

        @Override
        public synchronized void mark(int readlimit)
        {
            _marklimit = readlimit;
            _markpos = _pos;
        }

        @Override
        public synchronized void reset() throws IOException
        {
            if (_markpos < 0)
                throw new IOException("Resetting to invalid mark");
            _pos = _markpos;
        }
    }

    private static String unquoteOnly(String s)
    {
        return unquoteOnly(s, false);
    }

    /**
     * Unquote a string, NOT converting unicode sequences
     *
     * @param s The string to unquote.
     * @param lenient if true, will leave in backslashes that aren't valid escapes
     * @return quoted string
     */
    private static String unquoteOnly(String s, boolean lenient)
    {
        if (s == null)
            return null;
        if (s.length() < 2)
            return s;

        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (first != last || (first != '"' && first != '\''))
            return s;

        StringBuilder b = new StringBuilder(s.length() - 2);
        boolean escape = false;
        for (int i = 1; i < s.length() - 1; i++)
        {
            char c = s.charAt(i);

            if (escape)
            {
                escape = false;
                if (lenient && !isValidEscaping(c))
                {
                    b.append('\\');
                }
                b.append(c);
            }
            else if (c == '\\')
            {
                escape = true;
            }
            else
            {
                b.append(c);
            }
        }

        return b.toString();
    }

    private static String unquote(String s)
    {
        return unquote(s, false);
    }

    /**
     * Unquote a string.
     *
     * @param s The string to unquote.
     * @param lenient true if unquoting should be lenient to escaped content, leaving some alone, false if string unescaping
     * @return quoted string
     */
    private static String unquote(String s, boolean lenient)
    {
        if (s == null)
            return null;
        if (s.length() < 2)
            return s;

        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (first != last || (first != '"' && first != '\''))
            return s;

        StringBuilder b = new StringBuilder(s.length() - 2);
        boolean escape = false;
        for (int i = 1; i < s.length() - 1; i++)
        {
            char c = s.charAt(i);

            if (escape)
            {
                escape = false;
                switch (c)
                {
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case '\\':
                        b.append('\\');
                        break;
                    case '/':
                        b.append('/');
                        break;
                    case '"':
                        b.append('"');
                        break;
                    case 'u':
                        b.append((char)((TypeUtil.convertHexDigit((byte)s.charAt(i++)) << 24) + (TypeUtil.convertHexDigit((byte)s.charAt(i++)) << 16) + (TypeUtil.convertHexDigit((byte)s.charAt(i++)) << 8) + (TypeUtil.convertHexDigit((byte)s.charAt(i++)))));
                        break;
                    default:
                        if (lenient && !isValidEscaping(c))
                        {
                            b.append('\\');
                        }
                        b.append(c);
                }
            }
            else if (c == '\\')
            {
                escape = true;
            }
            else
            {
                b.append(c);
            }
        }

        return b.toString();
    }

    /**
     * Check that char c (which is preceded by a backslash) is a valid
     * escape sequence.
     */
    private static boolean isValidEscaping(char c)
    {
        return ((c == 'n') || (c == 'r') || (c == 't') || (c == 'f') || (c == 'b') || (c == '\\') || (c == '/') || (c == '"') || (c == 'u'));
    }
}
