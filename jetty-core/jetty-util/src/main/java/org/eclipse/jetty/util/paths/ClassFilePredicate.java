package org.eclipse.jetty.util.paths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.BiPredicate;

public class ClassFilePredicate implements BiPredicate<Path, BasicFileAttributes>
{
    private final List<String> IGNORED_DIRS = List.of("WEB-INF", "META-INF");

    @Override
    public boolean test(Path path, BasicFileAttributes basicFileAttributes)
    {
        if (!Files.exists(path) || !Files.isRegularFile(path))
        {
            return false;
        }

        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".class"))
        {
            return false;
        }

        // so far, so good.
        // let's make sure this class isn't in an Ignored directory though (eg: META-INF or WEB-INF).

        int count = path.getNameCount();
        for (int i = 0; i < count; i++)
        {
            if (IGNORED_DIRS.contains(path.getName(i).toString()))
                return false;
        }
        return true;
    }
}
