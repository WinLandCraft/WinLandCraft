package dev.winlandcraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;

/** Live laser pose, beam-origin, and motion controls. */
public final class LaserCalibrationPanel extends WorldPanel {
    private static final int ROW_TOP=118,ROW_HEIGHT=38,TRACK_X=306,TRACK_WIDTH=330;
    private LaserTuning.Section section=LaserTuning.Section.POSE;
    private int active=-1;
    private boolean changed;
    private String status="Changes save automatically.";

    public LaserCalibrationPanel(){super(3.4f,2f);}
    @Override public int pixelWidth(){return 780;}
    @Override public int pixelHeight(){return 460;}
    @Override public int titlebarHeight(){return 32;}
    @Override protected float minimumWidth(){return 1.7f;}
    @Override protected float minimumHeight(){return 1f;}

    @Override public void mouseDown(int x,int y,int button) {
        if(button!=0||y<0)return;
        if(y>=70&&y<104) {
            for(int index=0;index<3;index++)if(x>=24+index*132&&x<146+index*132) {
                section=LaserTuning.Section.values()[index];active=-1;return;
            }
        }
        int row=rowAt(y);
        if(row>=0) {
            var parameter=parameters()[row];
            if(x>=250&&x<286)change(parameter,-1);
            else if(x>=650&&x<686)change(parameter,1);
            else if(x>=TRACK_X-6&&x<TRACK_X+TRACK_WIDTH+6) {
                active=row;setFromTrack(parameter,x);
            }
            return;
        }
        if(section==LaserTuning.Section.MOTION&&y>=300&&y<336) {
            if(x>=24&&x<178){LaserPointer.previewSpin();status="Previewing color spin.";}
            else if(x>=194&&x<378){LaserPointer.previewBounce(Minecraft.getInstance());status="Previewing power bounce.";}
            return;
        }
        if(y>=410&&y<446&&x>=24&&x<146) {
            LaserTuning.reset(section);changed=true;save("Reset this section.");
        } else if(y>=410&&y<446&&x>=160&&x<350) {
            Minecraft.getInstance().keyboardHandler.setClipboard(LaserTuning.summary());
            status="Copied all tuning values.";
        }
    }

    @Override public void hover(int x,int y) {
        if(active>=0)setFromTrack(parameters()[active],x);
    }

    @Override public void mouseUp(int x,int y,int button) {
        if(button!=0)return;
        active=-1;
        if(changed)save("Calibration saved.");
    }

    @Override public void scroll(int x,int y,double amount) {
        int row=rowAt(y);
        if(row<0||amount==0)return;
        change(parameters()[row],(float)amount);save("Calibration saved.");
    }

    private LaserTuning.Parameter[] parameters(){return LaserTuning.parameters(section);}
    private void change(LaserTuning.Parameter parameter,float direction){LaserTuning.adjust(parameter,direction);changed=true;}
    private void setFromTrack(LaserTuning.Parameter parameter,int x){LaserTuning.setNormalized(parameter,(x-TRACK_X)/(float)TRACK_WIDTH);changed=true;}
    private void save(String message){status=ModSettings.save()?message:"Could not save; see latest.log.";changed=false;}
    private int rowAt(int y) {
        int row=(y-ROW_TOP)/ROW_HEIGHT;
        return y>=ROW_TOP&&row<parameters().length&&y<ROW_TOP+row*ROW_HEIGHT+30?row:-1;
    }

    @Override public void render(WorldRenderContext context) {
        try(var surface=surface(context)) {
            if(surface==null||!surface.frontFacing())return;
            var canvas=surface.canvas();
            canvas.rect(-3,-35,786,498,0,0xFF536579);canvas.rect(0,-32,780,32,.3f,0xFF3A5367);
            canvas.text("Laser Calibration",12,-22,-1,1.5f);renderUngroup(canvas);renderClose(canvas);
            canvas.rect(0,0,780,460,.1f,0xFF18212D);
            canvas.text("Tune the remote while holding it",24,18,-1,1.8f);
            canvas.text(instruction(),24,48,0xFFAAC2D3,1.12f);
            tab(canvas,LaserTuning.Section.POSE,"Pose",24);tab(canvas,LaserTuning.Section.BEAM,"Beam",156);
            tab(canvas,LaserTuning.Section.MOTION,"Motion",288);
            var parameters=parameters();
            for(int row=0;row<parameters.length;row++)renderRow(canvas,parameters[row],row);
            if(section==LaserTuning.Section.MOTION) {
                button(canvas,PixelIcon.REFRESH,"Preview spin",24,300,154);
                button(canvas,PixelIcon.ARROW_UP,"Preview power",194,300,184);
            }
            button(canvas,PixelIcon.UNDO,"Reset",24,410,122);button(canvas,PixelIcon.COPY,"Copy all",160,410,190);
            canvas.text(status,374,423,status.startsWith("Could not")?0xFFFFA5A5:0xFF9EB6C7,1.05f);
        }
    }

    private String instruction() {
        return switch(section) {
            case POSE -> "Align the remote body. Pitch/yaw are usually the useful first adjustments.";
            case BEAM -> "Move the emission point in model units; increase inset to pull it into the sensor.";
            case MOTION -> "Tune the body spin, power bounce, and layered power-click volume.";
        };
    }

    private void tab(PanelCanvas canvas,LaserTuning.Section target,String label,int x) {
        int color=section==target?0xFF3F7083:hoverColor(x,70,122,34,0xFF293E4E,0xFF38566A);
        canvas.rect(x,70,122,34,.35f,color);canvas.text(label,x+37,81,-1,1.2f);
    }

    private void renderRow(PanelCanvas canvas,LaserTuning.Parameter parameter,int row) {
        int y=ROW_TOP+row*ROW_HEIGHT;
        canvas.text(parameter.label,24,y+9,-1,1.2f);canvas.text(LaserTuning.display(parameter),170,y+9,0xFFFFD37A,1.15f);
        squareButton(canvas,PixelIcon.MINUS,250,y);
        canvas.rect(TRACK_X,y+13,TRACK_WIDTH,5,.35f,0xFF526878);
        float amount=LaserTuning.normalized(parameter);
        canvas.rect(TRACK_X,y+13,TRACK_WIDTH*amount,5,.4f,0xFF51CFDF);
        canvas.rect(TRACK_X-5+TRACK_WIDTH*amount,y+6,10,19,.5f,
                hovered(TRACK_X-6,y,TRACK_WIDTH+12,30)||active==row?0xFFFFFFFF:0xFFA9EAF0);
        squareButton(canvas,PixelIcon.PLUS,650,y);
    }

    private void squareButton(PanelCanvas canvas,PixelIcon icon,int x,int y) {
        canvas.rect(x,y,36,30,.35f,hoverColor(x,y,36,30,0xFF314858,0xFF49677B));
        icon.draw(canvas,x+6,y+3,24,.45f,-1);
    }
    private void button(PanelCanvas canvas,PixelIcon icon,String text,int x,int y,int width) {
        canvas.rect(x,y,width,36,.35f,hoverColor(x,y,width,36,0xFF314858,0xFF49677B));
        icon.draw(canvas,x+7,y+6,24,.45f,-1);canvas.text(text,x+39,y+12,-1,1.15f);
    }
}
