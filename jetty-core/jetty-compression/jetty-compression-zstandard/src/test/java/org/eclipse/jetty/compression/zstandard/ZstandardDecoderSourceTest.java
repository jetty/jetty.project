package org.eclipse.jetty.compression.zstandard;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ZstandardDecoderSourceTest extends AbstractZstdTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    public void testDecodeText(String textResourceName) throws Exception
    {
        startZstd();
        String compressedName = String.format("%s.%s", textResourceName, zstd.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        // TODO: sizedPool config of size 1?
        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = zstd.newDecoderSource(fileSource);

        String result = Content.Source.asString(decoderSource);
        String expected = Files.readString(uncompressed);
        assertEquals(expected, result);
    }
}
