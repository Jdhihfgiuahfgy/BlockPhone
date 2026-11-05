package dev.blockphone.fabric.client.screens;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.browser.WebEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * Один экран, который этот телефон сейчас показывает: страница по ссылке в Chromium (отдельный профиль без входов игрока),
 * свой скрипт на ней (видео на весь экран, управление, перехват звука) и источники звука в игре. Позиция видео
 * подгоняется под владельца экрана: расхождение больше 1,5 с — перемотка. Только поток рендера (звук — поток Chromium).
 */
final class ScreenPlayer {
    private static final String MARK = "__bpscr:";
    /**
     * Скрипт на странице экрана. Самое большое видео — на весь вид (остальное прячется), его звук уходит в Web Audio:
     * наружу страница молчит, а кусочки PCM (24 кГц, стерео, 16 бит) идут в консоль с меткой — когда звук нужен (want.c).
     * __bpSet({p: пауза, t: перемотка в секундах, l: повтор, c: звук}) — управление; раз в секунду — состояние видео.
     */
    private static final String SCRIPT = """
            (function(){if(window.__bpScr)return;window.__bpScr=1;var M='__bpscr:',T=Date.now();
            var st=document.createElement('style');var F='visibility:visible!important;position:fixed!important;left:0!important;top:0!important;'
            +'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;border:0!important;'
            +'z-index:2147483647!important;background:#000!important;transform:none!important;';
            st.textContent='html.__bpon,html.__bpon body{background:#000!important;overflow:hidden!important}'
            +'html.__bpon body *{visibility:hidden!important}'
            +'html.__bpon video.__bpv{'+F+'object-fit:contain!important}html.__bpon iframe.__bpif{'+F+'}'
            +'html.__bpon video::-webkit-media-controls,html.__bpon video::-webkit-media-controls-enclosure{display:none!important}';
            function css(){if(!st.parentNode){var p=document.head||document.documentElement;if(p)p.appendChild(st)}}css();
            var cur=null,ifr=null,ctx=null,proc=null,dir=[],want={p:null,t:null,l:false,c:false,f:true,r:1,dv:0},done=new WeakSet(),sur=0;
            var OK=/^(принять( все| всё)?|согласен|согласиться|я согласен|соглашаюсь|ок|ok|понятно|хорошо|продолжить|accept( all)?|allow( all)?|agree|i agree|got it|consent)$/i,
            CK=/cookie|consent|куки|gdpr|согласи|персональн|privacy/i;
            function consent(){if(Date.now()-T>30000)return;document.querySelectorAll('button,[role=button],a').forEach(function(b){
            var t=(b.innerText||'').trim();if(!t||t.length>40||!OK.test(t))return;var p=b,n=0;while(p&&n<7){var s=(p.id||'')+' '+(typeof p.className=='string'?p.className:'');
            if(CK.test(s)||(n>1&&CK.test((p.innerText||'').slice(0,400)))){try{b.click()}catch(e){}return}p=p.parentElement;n++}})}
            function bigFrame(){var best=null,ba=0;document.querySelectorAll('iframe').forEach(function(f){var r=f.getBoundingClientRect(),a=r.width*r.height;
            if(r.width>=200&&r.height>=110&&a>ba){ba=a;best=f}});return best}
            function pick(){var best=null,ba=-1;document.querySelectorAll('video').forEach(function(v){
            var a=(v.videoWidth||1)*(v.videoHeight||1)*(v.readyState>0?4:1)*(v.paused?1:2);if(a>ba){ba=a;best=v}});return best}
            function send(e){if(!want.c)return;var b=e.inputBuffer,nc=Math.min(6,b.numberOfChannels),D=[],i,c;
            for(c=0;c<nc;c++)D.push(b.getChannelData(c));var ch=2;if(nc>=6){for(c=2;c<6&&ch==2;c++){var d=D[c];
            for(i=0;i<d.length;i+=16){if(d[i]>0.0005||d[i]<-0.0005){ch=6;break}}}}if(ch==6)sur=Date.now();else if(Date.now()-sur<2000&&nc>=6)ch=6;
            if(nc<2){D.push(D[0])}else{var z=1;for(i=0;i<D[1].length;i+=16){if(D[1][i]!==0){z=0;break}}if(z)D[1]=D[0]}var n=D[0].length,u=new Uint8Array(n*ch*2),k=0;
            for(i=0;i<n;i++){for(c=0;c<ch;c++){var v=Math.max(-1,Math.min(1,D[c][i]))*32767|0;u[k++]=v&255;u[k++]=(v>>8)&255}}
            var s='';for(var j=0;j<u.length;j+=4096){s+=String.fromCharCode.apply(null,u.subarray(j,j+4096))}console.log(M+'a'+ch+btoa(s))}
            function taint(v){var s=v.currentSrc||v.src;if(!s)return null;if(/^(blob|data|mediasource):/i.test(s))return false;
            try{return new URL(s,location.href).origin!==location.origin&&!v.crossOrigin}catch(e){return false}}
            function direct(v){if(dir.indexOf(v)<0)dir.push(v);v.muted=false;v.volume=Math.max(0,Math.min(1,want.dv||0));console.log(M+'d1')}
            function hook(v){if(done.has(v))return;var t=taint(v);if(t===null){v.volume=0;return}done.add(v);
            if(t){direct(v);return}try{if(!ctx)mk();ctx.createMediaElementSource(v).connect(proc);v.volume=1}catch(e){console.log(M+'e'+e);direct(v)}}
            function mk(){ctx=new AudioContext({sampleRate:24000});var g=ctx.createGain();g.gain.value=0;g.connect(ctx.destination);
            proc=ctx.createGain();proc.channelCount=6;proc.channelCountMode='explicit';proc.channelInterpretation='discrete';
            function sp(r){var p=ctx.createScriptProcessor(1024,6,1);p.channelCount=6;p.channelCountMode='explicit';
            p.channelInterpretation='discrete';p.onaudioprocess=send;proc.connect(p);p.connect(g);console.log(M+'e script '+r)}
            try{ctx.audioWorklet.addModule('bpcap://w/cap.js').then(function(){var w=new AudioWorkletNode(ctx,'bpcap',{numberOfInputs:1,numberOfOutputs:1,
            channelCount:6,channelCountMode:'explicit',channelInterpretation:'discrete'});w.port.onmessage=function(e){var d=e.data;
            send({inputBuffer:{numberOfChannels:d.length,getChannelData:function(c){return d[c]}}})};proc.connect(w);w.connect(g);
            console.log(M+'e worklet')}).catch(function(e){sp(''+e)})}catch(e){sp(''+e)}}
            document.addEventListener('play',function(e){var t=e.target;if(t&&(t.tagName==='VIDEO'||t.tagName==='AUDIO'))hook(t)},true);
            try{var P=HTMLMediaElement.prototype,op=P.play;P.play=function(){try{hook(this)}catch(e){}return op.apply(this,arguments)}}catch(e){}
            new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.nodeType!==1)return;
            if(n.tagName==='VIDEO'||n.tagName==='AUDIO')hook(n);else if(n.querySelectorAll)n.querySelectorAll('video,audio').forEach(hook)})})})
            .observe(document,{childList:true,subtree:true});
            function loop(){css();consent();var v=pick();if(v!==cur){if(cur)cur.classList.remove('__bpv');cur=v;if(v)v.classList.add('__bpv')}
            var f=v?null:bigFrame();if(f!==ifr){if(ifr)ifr.classList.remove('__bpif');ifr=f;if(f){f.classList.add('__bpif');try{f.allow='autoplay; fullscreen'}catch(e){}}}
            if(document.documentElement)document.documentElement.classList.toggle('__bpon',!!want.f&&!!(v||f));
            document.querySelectorAll('video,audio').forEach(hook);if(ctx&&ctx.state!=='running')ctx.resume();
            if(!v)return;v.loop=!!want.l;v.controls=false;if(want.t!=null){if(isFinite(v.duration))v.currentTime=Math.max(0,want.t);want.t=null}
            dir.forEach(function(d){var dv=Math.max(0,Math.min(1,want.dv||0));if(Math.abs(d.volume-dv)>0.01)d.volume=dv});
            var rt=want.r||1;if(Math.abs(v.playbackRate-rt)>0.001){try{v.preservesPitch=true;v.playbackRate=rt}catch(e){}}
            if(want.p===true&&!v.paused)v.pause();if(want.p===false&&v.paused&&!v.ended)v.play().catch(function(){})}
            setInterval(loop,250);
            setInterval(function(){var v=cur;if(v)console.log(M+'s'+JSON.stringify({h:1,t:v.currentTime,d:isFinite(v.duration)?v.duration:-1,
            p:v.paused,e:v.ended}))},1000);
            window.__bpSet=function(o){for(var k in o)want[k]=o[k];loop()};})();
            """;

    /** Сколько действий на странице можно записать. */
    static final int MAX_ACTIONS = 60;
    /** Служебные клавиши, которые записываются (текст — нет: пароли не должны уйти другим). */
    private static final java.util.Set<Integer> RECORD_KEYS = java.util.Set.of(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,
            org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE,
            org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, org.lwjgl.glfw.GLFW.GLFW_KEY_UP,
            org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN,
            org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, org.lwjgl.glfw.GLFW.GLFW_KEY_END);

    final String key;
    // Загрузка страницы и повтор записанных действий.
    private long loadStartedAt = System.currentTimeMillis();
    private int loadId;
    private int replayedLoad = -1;
    private int replayIndex;
    private long lastReplayAt;
    private long lastReplayT;
    private int loadedRev;
    /** Когда страница этой загрузки догрузилась (0 — ещё грузится). */
    private long loadedAt;
    /** Ведущий экран (его ссылка и проигрывание; у объединённой группы они у всех одинаковые). */
    Screens.Live live;
    /** Все экраны, что показывают эту вкладку (один — или вся объединённая группа). */
    java.util.List<Screens.Live> members;
    private final WebEngine.View view;
    private final ScreenAudio audio = new ScreenAudio();
    private final Map<String, StreamSound> sounds = new HashMap<>();
    private String loaded;
    long unwantedSince;
    // Состояние видео на странице (из её сообщений).
    private volatile boolean hasVideo;
    private volatile double pagePos;
    private volatile double pageDur = -1;
    private volatile boolean pagePaused = true;
    private volatile long reportAt;
    private long lastCommand;
    private long lastSeek;
    private long lastUrlCheck;
    /** Громкость видео, чей звук не перехватить (чужой сайт без CORS) — играет из браузера, громкость считаем сами. */
    private double directGain;
    private volatile boolean hasDirect;
    /** Скорость видео для плавной подгонки к владельцу. */
    private double rate = 1;

    /**
     * Сборщик звука страницы в AudioWorklet (свой поток звука браузера — не зависит от загрузки страницы, в отличие от
     * ScriptProcessor, который на тяжёлых сайтах вроде YouTube пропускал до трети звука). Отдаётся с адреса bpcap://w/cap.js
     * (WebEngine.putCode) — его не блокируют правила сайта.
     */
    static final String WORKLET = """
            class P extends AudioWorkletProcessor{constructor(){super();this.b=null;this.n=0}
            process(I){var i=I[0];if(!i||!i.length)return true;var c=i.length,f=i[0].length,k;
            if(!this.b||this.b.length!==c){this.b=[];for(k=0;k<c;k++)this.b.push(new Float32Array(2048));this.n=0}
            for(k=0;k<c;k++)this.b[k].set(i[k],this.n);this.n+=f;
            if(this.n>=2048){this.port.postMessage(this.b,this.b.map(function(x){return x.buffer}));this.b=null;this.n=0}return true}}
            registerProcessor('bpcap',P);
            """;

    static {
        dev.blockphone.fabric.client.browser.WebEngine.putCode("cap.js", WORKLET);
    }
    private long usedReport;
    private String lastSent = "";

    ScreenPlayer(String key, java.util.List<Screens.Live> members) {
        Screens.Live live = members.get(0);
        this.key = key;
        this.live = live;
        this.members = members;
        this.loaded = live.def().url;
        this.view = WebEngine.open(ScreenUrls.forScreen(live.def().url), true);
        this.loadedRev = live.def().rev;
        view.setLoadScript(SCRIPT, true); // и во встроенных окнах — плееры сайтов часто в iframe
        view.setDirectConsoleListener(this::onConsole);
    }

    /** Сообщения страницы — в потоке Chromium: звук сразу в кольцо, состояние — в поля. */
    private void onConsole(String message) {
        if (!message.startsWith(MARK) || message.length() < MARK.length() + 1) {
            return;
        }
        char kind = message.charAt(MARK.length());
        String body = message.substring(MARK.length() + 1);
        try {
            switch (kind) {
                case 'a' -> {
                    // «a<число каналов><base64>»: 2 — стерео, 6 — 5.1.
                    int ch = body.isEmpty() ? 2 : body.charAt(0) - '0';
                    audio.push(Base64.getDecoder().decode(body.substring(1)), ch == 6 ? 6 : 2);
                }
                case 's' -> {
                    JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                    hasVideo = o.get("h").getAsInt() == 1;
                    if (hasVideo) {
                        pagePos = o.get("t").getAsDouble();
                        pageDur = o.get("d").getAsDouble();
                        pagePaused = o.get("p").getAsBoolean();
                    }
                    reportAt = System.currentTimeMillis();
                }
                case 'e' -> BlockPhoneFabric.LOGGER.info("[screens] page audio: {}", body);
                case 'd' -> hasDirect = true;
                default -> {
                }
            }
        } catch (RuntimeException e) {
            BlockPhoneFabric.LOGGER.debug("[screens] bad page message: {}", e.toString());
        }
    }

    /** Каждый такт: ссылка, пауза/перемотка под владельца, источники звука. */
    void tick(Set<String> listening, double othersVolume) {
        ScreenData.Screen def = live.def();
        if (!def.url.equals(loaded)) {
            loaded = def.url;
            view.load(ScreenUrls.forScreen(def.url));
            newLoad();
            long fade = System.currentTimeMillis();
            for (StreamSound s : sounds.values()) {
                s.fadeFrom = fade; // новая ссылка — звук заново с тишины
            }
            hasVideo = false;
            lastSeek = 0;
            loadedRev = def.rev;
        }
        if (def.rev != loadedRev) {
            loadedRev = def.rev; // та же ссылка заново / действия сбросили — страница с чистого листа
            view.load(ScreenUrls.forScreen(def.url));
            newLoad();
            long fade = System.currentTimeMillis();
            for (StreamSound s : sounds.values()) {
                s.fadeFrom = fade;
            }
            hasVideo = false;
            lastSeek = 0;
        }
        // YouTube сам открывает следующее видео — вернуть то, что поставил владелец.
        long checkNow = System.currentTimeMillis();
        if (checkNow - lastUrlCheck > 2000 && !view.loading()) {
            lastUrlCheck = checkNow;
            String want = ScreenUrls.youtubeId(def.url);
            String have = ScreenUrls.youtubeId(view.url());
            if (want != null && have != null && !want.equals(have)) {
                view.load(ScreenUrls.forScreen(def.url));
                newLoad();
                hasVideo = false;
                lastSeek = 0;
            }
        }
        view.keepAlive();
        long now = System.currentTimeMillis();
        replay(def, now);
        if (hasVideo && now - reportAt > 3000) {
            hasVideo = false; // видео пропало со страницы (сообщения только от окна, где оно есть)
        }
        boolean capture = updateSounds(listening, othersVolume);
        // Управление страницей: при изменении сразу, иначе раз в 2 с (страница могла перезагрузиться).
        // Позиция: та, что у владельца (по его часам с поправкой на разницу часов). Большое расхождение — перемотка,
        // небольшое — плавно: видео чуть быстрее или медленнее (высота звука сохраняется), пока не догонит.
        if (hasVideo && pageDur > 0 && now - reportAt < 1500 && reportAt != usedReport) {
            usedReport = reportAt;
            double target = def.positionAt(now - live.skew()) / 1000.0;
            if (def.loop) {
                target = target % pageDur;
            } else {
                target = Math.min(target, pageDur);
            }
            double page = pagePos + (pagePaused ? 0 : (now - reportAt) / 1000.0);
            double drift = page - target;
            if (def.paused || Math.abs(drift) > 2.0) {
                rate = 1;
                if (Math.abs(drift) > 2.0 && now - lastSeek > 3000) {
                    lastSeek = now;
                    view.evalAll("window.__bpSet&&__bpSet({t:" + String.format(java.util.Locale.ROOT, "%.2f", target) + "})");
                }
            } else if (Math.abs(drift) > 0.3 || (rate != 1 && Math.abs(drift) > 0.08)) {
                // Догнать примерно за 6 с, но не быстрее ±3 % (сильнее — звук «плывёт»); до 0,3 с — не трогать.
                rate = 1 - Math.max(-0.03, Math.min(0.03, drift / 6));
            } else {
                rate = 1;
            }
        }
        String dv = String.format(java.util.Locale.ROOT, "%.2f", directGain);
        String state = def.paused + "/" + def.loop + "/" + capture + "/" + def.fit + "/" + String.format(java.util.Locale.ROOT, "%.3f", rate)
                + "/" + dv;
        if (view.ready() && (!state.equals(lastSent) || now - lastCommand > 2000)) {
            lastSent = state;
            lastCommand = now;
            view.evalAll("window.__bpSet&&__bpSet({p:" + def.paused + ",l:" + def.loop + ",c:" + capture + ",f:" + def.fit + ",r:"
                    + String.format(java.util.Locale.ROOT, "%.3f", rate) + ",dv:" + dv + "})");
        }
    }

    /** Страница загружается заново: записанные действия повторятся с начала. */
    private void newLoad() {
        loadStartedAt = System.currentTimeMillis();
        loadedAt = 0;
        loadId++;
        replayIndex = 0;
        lastReplayAt = 0;
        lastReplayT = 0;
    }

    /** Повтор записанных владельцем действий — по времени от начала загрузки, с теми же промежутками. */
    private void replay(ScreenData.Screen def, long now) {
        if (def.actions == null || def.actions.isEmpty() || replayedLoad == loadId || !view.ready()) {
            return;
        }
        if (loadedAt == 0) {
            if (view.loading() || now - loadStartedAt < 500) {
                return;
            }
            loadedAt = now;
        }
        int pw = view.pageWidth();
        int ph = view.pageHeight();
        while (replayIndex < def.actions.size()) {
            String[] p = def.actions.get(replayIndex).split(",");
            long t;
            int a;
            int b;
            int c;
            try {
                t = Long.parseLong(p[0]);
                a = Integer.parseInt(p[2]);
                b = Integer.parseInt(p[3]);
                c = Integer.parseInt(p[4]);
            } catch (RuntimeException e) {
                replayIndex++;
                continue;
            }
            // Первое — через 1,5 с после загрузки (или как у владельца, если он успел за 6 с), дальше — с его паузами, но не
            // дольше 4 с: владелец мог настраивать страницу минутами.
            long due = lastReplayAt == 0 ? Math.max(loadedAt + 1500, loadStartedAt + Math.min(t, 6000))
                    : lastReplayAt + Math.max(40, Math.min(4000, t - lastReplayT));
            if (now < due) {
                return;
            }
            int x = (int) Math.round(a / 10000.0 * pw);
            int y = (int) Math.round(b / 10000.0 * ph);
            switch (p[1]) {
                case "d" -> {
                    view.mouseMove(x, y);
                    view.mouseButton(x, y, c, true);
                }
                case "u" -> view.mouseButton(x, y, c, false);
                case "w" -> view.wheel(x, y, c / 100.0);
                case "k" -> view.key(a, b, c);
                default -> {
                }
            }
            BlockPhoneFabric.LOGGER.info("[screens] replay {} on {}", def.actions.get(replayIndex), key);
            lastReplayAt = now;
            lastReplayT = t;
            replayIndex++;
        }
        replayedLoad = loadId;
    }

    /**
     * Действие владельца на странице для записи (null — не записывается). Время — от начала загрузки этой страницы; в этой
     * загрузке у владельца повтора уже не будет (он сделал это сам).
     */
    @Nullable String record(char kind, double px, double py, int c) {
        if (kind == 'k' && !RECORD_KEYS.contains((int) px)) {
            return null;
        }
        replayedLoad = loadId;
        long t = Math.min(9_999_999, System.currentTimeMillis() - loadStartedAt);
        if (kind == 'k') {
            return t + ",k," + (int) px + "," + (int) py + "," + c;
        }
        int x = (int) Math.round(Math.max(0, Math.min(1, px / Math.max(1, view.pageWidth()))) * 10000);
        int y = (int) Math.round(Math.max(0, Math.min(1, py / Math.max(1, view.pageHeight()))) * 10000);
        return t + "," + kind + "," + x + "," + y + "," + c;
    }

    /** Звук: «с телефона» (подключился) или из колонок / самого экрана. true — звук со страницы нужен. */
    private boolean updateSounds(Set<String> listening, double othersVolume) {
        Minecraft mc = Minecraft.getInstance();
        ScreenData.Screen def = live.def();
        Map<String, Vec3> want = new HashMap<>();
        if (mc.player != null) {
            boolean listen = false;
            boolean anySpeakers = false;
            for (Screens.Live m : members) {
                listen |= listening.contains(m.key());
                anySpeakers |= !m.speakers().isEmpty();
            }
            if (listen) {
                want.put("phone", null);
            } else if (!def.phone) {
                Vec3 eye = mc.player.getEyePosition();
                double reach = def.radius + 8;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Screens.Live m : members) {
                    if (!anySpeakers) {
                        // Колонок нет — звук из каждого экрана группы.
                        Vec3 c = m.def().soundPos();
                        if (c.distanceToSqr(eye) < reach * reach) {
                            want.put("c:" + m.def().id + "@" + def.radius, c);
                        }
                    }
                    for (ScreenData.Speaker sp : m.speakers()) {
                        Vec3 c = sp.center();
                        if (seen.add(sp.id) && m.set().speakerOn(sp) && c.distanceToSqr(eye) < reach * reach) {
                            want.put("s:" + sp.id + ":" + sp.x + "," + sp.y + "," + sp.z + "@" + def.radius + "/" + sp.channel, c);
                        }
                    }
                }
            }
        }
        double base = def.volume / 100.0 * live.set().screenGain(def) * (live.mine() ? 1 : othersVolume);
        sounds.entrySet().removeIf(e -> {
            boolean keep = want.containsKey(e.getKey()) && !e.getValue().dead();
            if (!keep) {
                e.getValue().finish();
            }
            return !keep;
        });
        for (Map.Entry<String, Vec3> e : want.entrySet()) {
            if (!sounds.containsKey(e.getKey()) && base > 0) {
                Vec3 pos = e.getValue();
                String speakerId = e.getKey().startsWith("s:") ? e.getKey().substring(2, e.getKey().indexOf(':', 2)) : null;
                DoubleSupplier gain = () -> live.def().volume / 100.0 * live.set().screenGain(live.def()) * speakerVolume(speakerId)
                        * (live.mine() ? 1 : othersVolume);
                ScreenAudio.Pick pick = pos == null ? ScreenAudio.Pick.STEREO : speakerPick(speakerId);
                StreamSound s = new StreamSound(audio, audio.reader(pick), pos, def.radius, gain);
                sounds.put(e.getKey(), s);
                s.start();
            }
        }
        directGain = 0;
        if (mc.player != null && base > 0) {
            float category = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)
                    * mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.BLOCKS);
            if (want.containsKey("phone")) {
                directGain = base * category;
            } else {
                Vec3 eye = mc.player.getEyePosition();
                for (Vec3 p : want.values()) {
                    if (p != null) {
                        directGain = Math.max(directGain, base * category * Math.max(0, 1 - p.distanceTo(eye) / Math.max(1, def.radius)));
                    }
                }
            }
        }
        return !sounds.isEmpty();
    }

    /** Колонка экранов этой вкладки по id (null — нет). */
    private @Nullable ScreenData.Speaker findSpeaker(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Screens.Live m : members) {
            for (ScreenData.Speaker sp : m.speakers()) {
                if (sp.id.equals(id)) {
                    return sp;
                }
            }
        }
        return null;
    }

    /** Канал колонки; null — звук из самого экрана (всё в моно). */
    private ScreenAudio.Pick speakerPick(@Nullable String id) {
        ScreenData.Speaker sp = findSpeaker(id);
        return sp == null ? ScreenAudio.Pick.MIX : ScreenAudio.Pick.of(sp.channel);
    }

    ScreenAudio audio() {
        return audio;
    }

    /** Сколько каналов в звуке страницы (для приложения). */
    int channels() {
        return audio.channels();
    }

    /** Своя громкость колонки (0..1); null — звук из самого экрана или «с телефона». */
    private double speakerVolume(@Nullable String id) {
        ScreenData.Speaker sp = findSpeaker(id);
        return sp == null ? 1 : sp.volume / 100.0 * live.set().speakerGain(sp);
    }

    /** Текстура кадра для рисования в мире (null — ещё нет). */
    @Nullable Identifier texture() {
        ScreenData.Screen def = live.def();
        int wb = Math.max(1, def.widthBlocks());
        int hb = Math.max(1, def.heightBlocks());
        int w = Math.max(256, Math.min(1280, wb * 128));
        int h = Math.round(w * hb / (float) wb);
        if (h > 1280) {
            h = 1280;
            w = Math.max(64, Math.round(h * wb / (float) hb));
        }
        return view.frameTexture(w, Math.max(64, h));
    }

    String debug() {
        return key + " url=" + view.url() + " loading=" + view.loading() + " video=" + hasVideo + " pos=" + String.format(java.util.Locale.ROOT, "%.1f", pagePos)
                + "/" + String.format(java.util.Locale.ROOT, "%.1f", pageDur) + " paused=" + pagePaused + " audioLive=" + audio.live() + (hasDirect ? " direct" : "") + " [" + audio.stats() + " rate=" + rate + "]"
                + " sounds=" + sounds.keySet() + " active=" + sounds.values().stream().filter(x -> !x.dead()).count() + " tex=" + view.textureWidth() + "x" + view.textureHeight();
    }

    boolean hasVideo() {
        return hasVideo;
    }

    /** Позиция и длина видео на странице (с) — для приложения. */
    double pagePosition() {
        return pagePos + (pagePaused || !hasVideo ? 0 : (System.currentTimeMillis() - reportAt) / 1000.0);
    }

    double pageDuration() {
        return pageDur;
    }

    WebEngine.View view() {
        return view;
    }

    void close() {
        for (StreamSound s : sounds.values()) {
            s.finish();
        }
        sounds.clear();
        view.close();
    }
}
