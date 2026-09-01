package dev.winlandcraft;

import java.nio.file.Path;
import java.nio.charset.*;
import java.util.*;

/** Editable text and caret/selection, independent of Minecraft and disk I/O. */
final class NoteDocument {
    Path path;String text="",saved="";Charset charset=StandardCharsets.UTF_8;
    byte[] bom=new byte[0],original;String newline=System.lineSeparator();
    int caret,anchor,scroll,columnScroll;boolean busy;
    private final Deque<State> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private String cachedText;private String[] cachedLines;
    String[] lines(){if(cachedText!=text){cachedText=text;cachedLines=text.split("\n",-1);}return cachedLines;}
    private record State(String text,int caret,int anchor){}
    boolean dirty(){return !text.equals(saved);}
    String name(){return path==null?"Untitled":path.getFileName().toString();}
    int low(){return Math.min(caret,anchor);}int high(){return Math.max(caret,anchor);}
    String selection(){return text.substring(low(),high());}
    void replace(String value) {
        value=value.replace("\r\n","\n").replace('\r','\n');
        if(text.length()-(high()-low())+value.length()>NoteFiles.LIMIT)return;
        undo.push(new State(text,caret,anchor));while(undo.size()>32)undo.removeLast();
        long retained=undo.stream().mapToLong(s->s.text.length()).sum();while(retained>NoteFiles.LIMIT*2L&&undo.size()>1)retained-=undo.removeLast().text.length();redo.clear();
        int start=low();text=text.substring(0,start)+value+text.substring(high());caret=anchor=start+value.length();
    }
    void move(int next,boolean select){caret=Math.clamp(next,0,text.length());if(!select)anchor=caret;}
    void horizontal(int direction,boolean select) {
        if(!select&&caret!=anchor){move(direction<0?low():high(),false);return;}
        if(direction<0&&caret>0)move(text.offsetByCodePoints(caret,-1),select);
        if(direction>0&&caret<text.length())move(text.offsetByCodePoints(caret,1),select);
    }
    void erase(boolean backwards) {
        if(caret==anchor) {
            if(backwards&&caret>0)anchor=text.offsetByCodePoints(caret,-1);
            else if(!backwards&&caret<text.length())anchor=text.offsetByCodePoints(caret,1);
            else return;
        }
        replace("");
    }
    int lineStart(){return text.lastIndexOf('\n',Math.max(-1,caret-1))+1;}
    int lineEnd(){int end=text.indexOf('\n',caret);return end<0?text.length():end;}
    void vertical(int direction,boolean select){int start=lineStart(),column=caret-start;if(direction<0&&start>0){int previous=text.lastIndexOf('\n',start-2)+1;move(Math.min(previous+column,start-1),select);}else if(direction>0){int end=lineEnd();if(end<text.length()){int next=text.indexOf('\n',end+1);move(Math.min(end+1+column,next<0?text.length():next),select);}}}
    void undo(boolean forward){var from=forward?redo:undo;var to=forward?undo:redo;if(from.isEmpty())return;to.push(new State(text,caret,anchor));var state=from.pop();text=state.text;caret=state.caret;anchor=state.anchor;}
}
