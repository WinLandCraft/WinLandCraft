package dev.winlandcraft;
import java.nio.file.Path;
import java.util.Locale;

final class ImageViewerPage {
    static String mime(Path path){String n=path.getFileName().toString().toLowerCase(Locale.ROOT);return switch(n.substring(n.lastIndexOf('.')+1)){
        case "png","apng"->"image/png";case "jpg","jpeg","jfif","pjpeg","pjp"->"image/jpeg";
        case "gif"->"image/gif";case "webp"->"image/webp";case "avif"->"image/avif";
        case "bmp"->"image/bmp";case "ico"->"image/x-icon";case "svg"->"image/svg+xml";
        default->"application/octet-stream";
    };}
    static boolean supports(Path path){return !mime(path).equals("application/octet-stream");}
    static String html(boolean loaded){return """
        <!doctype html><html><head><meta charset="utf-8"><title>Image Viewer</title>
        <style>
        *{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:#151c25;color:#e6edf5;font:16px sans-serif;overflow:hidden}
        header{height:44px;display:flex;align-items:center;gap:8px;padding:6px 12px;background:#233344}button{background:#3b5369;color:white;border:0;border-radius:4px;padding:6px 14px;cursor:pointer}button:hover{background:#55768f}button:disabled{opacity:.4;cursor:default}
        #stage{position:absolute;inset:44px 0 0;overflow:auto;display:flex;background:repeating-conic-gradient(#1d2732 0% 25%,#26323f 0% 50%) 0 0/24px 24px}
        img{flex:none;margin:auto;object-fit:contain}#message{position:absolute;inset:44px 0 0;display:grid;place-content:center;padding:32px;pointer-events:none;text-align:center}#details{margin-left:auto;color:#b5c9d9}
        </style></head><body><header><button id="fit">Fit</button><button id="actual">100%</button><button id="minus" aria-label="Zoom out">-</button><span id="zoom">--</span><button id="plus" aria-label="Zoom in">+</button><span id="details"></span></header>
        <div id="stage"><img hidden draggable="false" alt=""></div><div id="message"></div><script>
        const image=document.querySelector('img'),stage=document.querySelector('#stage'),message=document.querySelector('#message'),buttons=[...document.querySelectorAll('button')];
        let fitting=true,scale=1,ready=false;
        function layout(){if(!ready)return;if(fitting)scale=Math.min(stage.clientWidth/image.naturalWidth,stage.clientHeight/image.naturalHeight,1);image.style.width=(image.naturalWidth*scale)+'px';image.style.height=(image.naturalHeight*scale)+'px';document.querySelector('#zoom').textContent=Math.round(scale*100)+'%';}
        function change(value){fitting=false;scale=Math.max(.05,Math.min(8,value));layout();}
        document.querySelector('#fit').onclick=()=>{fitting=true;layout();};document.querySelector('#actual').onclick=()=>change(1);
        document.querySelector('#minus').onclick=()=>change(scale/1.25);document.querySelector('#plus').onclick=()=>change(scale*1.25);
        buttons.forEach(b=>b.disabled=true);window.addEventListener('resize',layout);
        image.onload=()=>{ready=true;image.hidden=false;message.textContent='';buttons.forEach(b=>b.disabled=false);document.querySelector('#details').textContent=image.naturalWidth+' x '+image.naturalHeight;layout();};
        image.onerror=()=>{ready=false;image.hidden=true;message.textContent='Cannot open this image. The file may be unreadable, damaged, or unsupported.';buttons.forEach(b=>b.disabled=true);};
        """+(loaded?"message.textContent='Loading image...';image.src='media';":"message.textContent='Drop an image here, or open one from File Manager.';")+"</script></body></html>";}
}
