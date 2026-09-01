package dev.winlandcraft;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class NoteChecks {
    static void run() throws Exception {
        var document=new NoteDocument();document.replace("one\r\ntwo");check(document.text.equals("one\ntwo"),"newline normalization");
        document.move(0,false);document.move(3,true);document.replace("first");check(document.text.equals("first\ntwo"),"selection replacement");
        document.undo(false);check(document.text.equals("one\ntwo"),"undo");document.undo(true);check(document.text.equals("first\ntwo"),"redo");
        document.move(document.text.length(),false);document.replace("\uD83D\uDE00");document.erase(true);check(document.text.equals("first\ntwo"),"unicode backspace");
        Path root=Files.createTempDirectory("winlandcraft-notepad-check");
        Path utf=root.resolve("note.txt"),binary=root.resolve("binary"),large=root.resolve("large"),copy=root.resolve("copy.txt");
        try {
            byte[] bom={(byte)0xff,(byte)0xfe};
            byte[] original=NoteFiles.encode("hello\nworld",StandardCharsets.UTF_16LE,bom,"\r\n");Files.write(utf,original);
            var loaded=NoteFiles.load(utf);check(loaded.text().equals("hello\nworld")&&loaded.newline().equals("\r\n"),"UTF16 load");
            check(Arrays.equals(original,NoteFiles.encode(loaded.text(),loaded.charset(),loaded.bom(),loaded.newline())),"encoding/BOM/line-ending round trip");
            byte[] changed=NoteFiles.encode("changed",loaded.charset(),loaded.bom(),loaded.newline());NoteFiles.save(utf,changed,original);
            check(Arrays.equals(changed,Files.readAllBytes(utf)),"save edited text");
            rejects(()->NoteFiles.save(utf,original,original),"external change conflict");check(Arrays.equals(changed,Files.readAllBytes(utf)),"conflict does not overwrite");
            NoteFiles.save(copy,changed,null);rejects(()->NoteFiles.save(copy,original,null),"Save As does not overwrite");
            Files.write(binary,new byte[]{0,1,2});rejects(()->NoteFiles.load(binary),"binary input");
            Files.write(binary,new byte[]{(byte)0xc3,0x28});rejects(()->NoteFiles.load(binary),"invalid UTF8");
            Files.write(large,new byte[NoteFiles.LIMIT+1]);rejects(()->NoteFiles.load(large),"large input");
            rejects(()->NoteFiles.load(root),"directory input");rejects(()->NoteFiles.load(root.resolve("missing")),"missing input");
        } finally {for(Path p:new Path[]{utf,binary,large,copy})Files.deleteIfExists(p);Files.delete(root);}
        System.out.println("Notepad: editing, selection, undo/redo, Unicode, encoding round-trip, save conflicts and invalid file handling passed.");
    }
    interface Action{void run()throws Exception;}
    private static void rejects(Action action,String label)throws Exception{try{action.run();}catch(java.io.IOException expected){return;}throw new AssertionError(label);}
    private static void check(boolean result,String label){if(!result)throw new AssertionError(label);}
}
