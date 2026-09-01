package dev.winlandcraft;

import java.nio.file.*;

final class FileDirectoryChecks {
    static void run() throws Exception {
        Path root=Files.createTempDirectory("winlandcraft-directory-check");
        try {
            Files.createDirectory(root.resolve("z-folder"));Files.writeString(root.resolve("a-file.txt"),"hello");
            var listing=FileDirectory.read(root);
            if(!listing.error().isEmpty()||listing.entries().size()!=2||!listing.entries().get(0).directory()
                    ||listing.entries().get(1).size()!=5)throw new AssertionError("directory listing and sorting");
            if(FileDirectory.read(root.resolve("missing")).error().isEmpty())throw new AssertionError("missing folder");
            if(FileDirectory.read(root.resolve("a-file.txt")).error().isEmpty())throw new AssertionError("file is not navigable");
            if(!FileDirectory.read(root.resolve("z-folder")).entries().isEmpty())throw new AssertionError("empty folder");
            if(!Files.readString(root.resolve("a-file.txt")).equals("hello"))throw new AssertionError("read-only listing");
            System.out.println("File Manager: directories-first listing, file metadata, empty/missing folders, and read-only behavior passed.");
        } finally {
            Files.deleteIfExists(root.resolve("a-file.txt"));Files.deleteIfExists(root.resolve("z-folder"));Files.deleteIfExists(root);
        }
    }
}
