package dev.winlandcraft;
import java.util.*;

/** Native/plugin apps have one logical input focus; transfer it with balanced releases. */
final class RemoteAppInput {
    private UUID controller;
    private final Set<Integer> buttons=new HashSet<>(),keys=new HashSet<>();
    private boolean focused,dispatching;
    boolean dispatching(){return dispatching;}
    void accept(WorldPanel panel,StreamProtocol.Control input){
        if(input.event()==StreamProtocol.Control.CANCEL){if(input.controller().equals(controller))clear(panel);return;}
        if(!ModSettings.streamRemoteControl)return;
        if(!input.controller().equals(controller)){
            if(input.event()!=StreamProtocol.Control.MOUSE_DOWN&&input.event()!=StreamProtocol.Control.KEY&&input.event()!=StreamProtocol.Control.SCROLL
                    &&!(controller==null&&input.event()==StreamProtocol.Control.MOVE))return;
            clear(panel);controller=input.controller();
        }
        dispatching=true;
        try{switch(input.event()){
            case StreamProtocol.Control.MOVE->panel.pointerMoved(input.x(),input.y());
            case StreamProtocol.Control.MOUSE_DOWN->{if(buttons.add(input.value()))panel.mouseDown(input.x(),input.y(),input.value());if(!focused&&panel.wantsKeyboard()){panel.keyboardStarted();focused=true;}}
            case StreamProtocol.Control.MOUSE_UP->{if(buttons.remove(input.value()))panel.mouseUp(input.x(),input.y(),input.value());}
            case StreamProtocol.Control.SCROLL->panel.scroll(input.x(),input.y(),input.amount());
            case StreamProtocol.Control.KEY->{if(panel.acceptsKeyboard()){
                if(!focused){panel.keyboardStarted();focused=true;}
                if(input.action()==0){if(keys.remove(input.value()))panel.key(input.value(),input.scan(),0,input.modifiers());}
                else{keys.add(input.value());panel.key(input.value(),input.scan(),input.action(),input.modifiers());}
            }}
            case StreamProtocol.Control.CHARACTER->{if(panel.acceptsKeyboard()){if(!focused){panel.keyboardStarted();focused=true;}panel.character((char)input.value(),input.modifiers());}}
        }}finally{dispatching=false;}
    }
    void clear(WorldPanel panel){
        if(controller==null)return;controller=null;dispatching=true;
        var oldButtons=Set.copyOf(buttons);var oldKeys=Set.copyOf(keys);buttons.clear();keys.clear();boolean blur=focused;focused=false;
        try{for(int button:oldButtons)panel.mouseUp(-1,-1,button);for(int key:oldKeys)panel.key(key,0,0,0);if(blur)panel.keyboardStopped();panel.pointerMoved(-1,-1);}
        finally{dispatching=false;}
    }
}
