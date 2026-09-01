package dev.winlandcraft;

import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Bounded text I/O. Never launch a file or silently replace an externally changed file. */
final class NoteFiles {
    static final int LIMIT=1_048_576;
    record Loaded(Path path,String text,Charset charset,byte[] bom,String newline,byte[] original) {}
    static byte[] bytes(Path path) throws java.io.IOException {
        if(!Files.isRegularFile(path))throw new java.io.IOException("This is not a regular file.");
        try(var input=Files.newInputStream(path)) {
            byte[] data=input.readNBytes(LIMIT+1);
            if(data.length>LIMIT)throw new java.io.IOException("File exceeds the 1 MiB text limit.");
            return data;
        }
    }
    static Loaded load(Path path) throws java.io.IOException {
        Path real=path.toRealPath();byte[] data=bytes(real);int offset=0;Charset charset=StandardCharsets.UTF_8;
        if(data.length>=3&&data[0]==(byte)0xef&&data[1]==(byte)0xbb&&data[2]==(byte)0xbf)offset=3;
        else if(data.length>=2&&data[0]==(byte)0xff&&data[1]==(byte)0xfe){offset=2;charset=StandardCharsets.UTF_16LE;}
        else if(data.length>=2&&data[0]==(byte)0xfe&&data[1]==(byte)0xff){offset=2;charset=StandardCharsets.UTF_16BE;}
        String text;
        try{text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,data.length-offset)).toString();}
        catch(CharacterCodingException error){throw new java.io.IOException("Unsupported text encoding. Use UTF-8 or UTF-16 with a BOM.");}
        for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(ch==0||(ch<32&&ch!='\n'&&ch!='\r'&&ch!='\t'&&ch!='\f'))throw new java.io.IOException("This appears to be a binary file, not plain text.");}
        String newline=text.contains("\r\n")?"\r\n":text.contains("\r")?"\r":"\n";
        return new Loaded(real,text.replace("\r\n","\n").replace('\r','\n'),charset,Arrays.copyOf(data,offset),newline,data);
    }
    static byte[] encode(String text,Charset charset,byte[] bom,String newline) throws java.io.IOException {
        try {
            ByteBuffer encoded=charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text.replace("\n",newline)));
            if(encoded.remaining()+bom.length>LIMIT)throw new java.io.IOException("Text exceeds the 1 MiB save limit.");
            byte[] data=new byte[bom.length+encoded.remaining()];System.arraycopy(bom,0,data,0,bom.length);encoded.get(data,bom.length,encoded.remaining());return data;
        }catch(CharacterCodingException error){throw new java.io.IOException("Text contains an invalid Unicode character.");}
    }
    static void save(Path target,byte[] data,byte[] expected) throws java.io.IOException {
        target=target.toAbsolutePath().normalize();
        if(expected!=null&&!Arrays.equals(bytes(target),expected))throw new java.io.IOException("File changed outside Notepad. Use Save As to keep your edits.");
        if(expected==null&&Files.exists(target))throw new java.io.IOException("That file already exists. Choose a new name.");
        Path temp=Files.createTempFile(target.getParent(),".winlandcraft-note-",".tmp");
        try {
            Files.write(temp,data);
            if(expected==null)Files.move(temp,target);
            else {
                try {Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(target));}catch(UnsupportedOperationException ignored){}
                try {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
            }
        } finally {Files.deleteIfExists(temp);}
    }
}
