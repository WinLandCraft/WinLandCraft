package dev.winlandcraft;
import java.util.*;
final class RemoteAppInputChecks {
    static final class Panel extends WorldPanel {
        int downs,ups,presses,releases,chars,focus,blur,moves;boolean editing;
        Panel(){super(3,2);}
        public void mouseDown(int x,int y,int b){downs++;editing=true;}
        public void mouseUp(int x,int y,int b){ups++;}
        public void hover(int x,int y){moves++;}
        public boolean acceptsKeyboard(){return true;}
        public boolean wantsKeyboard(){return editing;}
        public void keyboardStarted(){focus++;}
        public void keyboardStopped(){blur++;editing=false;}
        public void key(int k,int scan,int action,int mods){if(action==0)releases++;else presses++;}
        public void character(char c,int mods){chars++;}
    }
    static void run(){
        boolean old=ModSettings.streamRemoteControl;var p=new Panel();UUID owner=UUID.randomUUID(),session=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        var click=StreamProtocol.Control.pointer(owner,session,a,StreamProtocol.Control.MOUSE_DOWN,20,30,0);
        try{
            ModSettings.streamRemoteControl=false;p.remoteControl(click);check(p.downs==0,"permission denied");
            ModSettings.streamRemoteControl=true;p.remoteControl(StreamProtocol.Control.pointer(owner,session,a,StreamProtocol.Control.MOVE,20,30,0));check(p.moves==1,"native hover");
            p.remoteControl(click);p.remoteControl(click);check(p.downs==1&&p.focus==1,"balanced press and focus");
            p.remoteControl(StreamProtocol.Control.key(owner,session,a,65,0,1,0));p.remoteControl(StreamProtocol.Control.character(owner,session,a,'a',0));check(p.presses==1&&p.chars==1,"native keyboard");
            p.remoteControl(StreamProtocol.Control.cancel(owner,session,b));check(p.ups==0,"other viewer cancellation isolated");
            p.remoteControl(StreamProtocol.Control.pointer(owner,session,b,StreamProtocol.Control.MOUSE_DOWN,40,30,0));check(p.ups==1&&p.releases==1&&p.blur==1&&p.downs==2,"controller handoff releases held inputs");
            ModSettings.streamRemoteControl=false;p.clearRemoteControls();p.clearRemoteControls();check(p.ups==2&&p.blur==2,"revoke and repeated cleanup");
            check(!p.remoteInput.dispatching(),"remote dispatch scope ends");
        }finally{ModSettings.streamRemoteControl=old;}
        System.out.println("Remote apps: permission gate, hover, click, keyboard, focus, controller handoff and cancellation passed.");
    }
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
