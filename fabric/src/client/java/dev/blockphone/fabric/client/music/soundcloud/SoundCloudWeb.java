package dev.blockphone.fabric.client.music.soundcloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.browser.WebEngine;
import dev.blockphone.fabric.client.music.MusicPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * SoundCloud как сайт во встроенном браузере (Chromium): у SoundCloud официальный API только с платной подпиской, а
 * сайт — со всем сразу (поиск, вход своим аккаунтом, лайки, плейлисты, «станции»). Вкладка одна на игру и живёт в фоне
 * (звук не прерывается, когда телефон закрыт). Раз в секунду свой скрипт на странице сообщает, что играет (название,
 * исполнитель, обложка, позиция, пауза) — для мини-плеера, HUD и клавиш музыки; управление — нажатием кнопок плеера сайта.
 * Только поток рендера.
 */
public final class SoundCloudWeb {
    public static final String HOME = "https://soundcloud.com/discover";
    private static final String MARK = "__bpsc:";
    /** Что играет: из Media Session и полосы плеера сайта. */
    private static final String POLL = "(function(){try{var q=function(s){return document.querySelector(s)};"
            + "var ms=navigator.mediaSession,md=ms&&ms.metadata;var t=md&&md.title,a=md&&md.artist,art=null;"
            + "if(md&&md.artwork&&md.artwork.length){art=md.artwork[md.artwork.length-1].src}"
            + "var tl=q('.playbackSoundBadge__titleLink');if(!t&&tl){t=tl.getAttribute('title')||tl.textContent.trim()}"
            + "var al=q('.playbackSoundBadge__lightLink');if(!a&&al){a=al.getAttribute('title')||al.textContent.trim()}"
            + "if(!art){var sp=q('.playbackSoundBadge .sc-artwork span');if(sp){var m=/url\\(\"?([^\")]+)/.exec(sp.style.backgroundImage);if(m)art=m[1]}}"
            + "var pw=q('.playbackTimeline__progressWrapper');var pos=pw?+pw.getAttribute('aria-valuenow'):0,dur=pw?+pw.getAttribute('aria-valuemax'):0;"
            + "var pc=q('.playControl');var playing=!!(pc&&pc.classList.contains('playing'));"
            + "console.log('" + MARK + "'+JSON.stringify({t:t||'',a:a||'',art:art||'',pos:pos||0,dur:dur||0,p:playing,u:location.href}))"
            + "}catch(e){}})();";

    public record Playing(String title, String artist, @Nullable String artwork, long positionMs, long durationMs, boolean playing) {
    }

    private static WebEngine.@Nullable View view;
    private static @Nullable Playing playing;
    private static long lastPoll;
    private static long lastState;
    private static boolean wasPlaying;

    /** Вкладка SoundCloud (создаётся при первом обращении; Chromium при первом запуске скачивается). */
    public static WebEngine.View view() {
        if (view == null) {
            view = WebEngine.open(HOME);
            view.setConsoleListener(SoundCloudWeb::onConsole);
        }
        return view;
    }

    public static boolean opened() {
        return view != null;
    }

    /** Что играет на сайте (null — ничего или давно нет вестей со страницы). */
    public static @Nullable Playing playing() {
        Playing p = playing;
        if (p == null || System.currentTimeMillis() - lastState > 5000 || p.title().isEmpty()) {
            return null;
        }
        return p;
    }

    /** Каждый такт: держать страницу живой и раз в секунду спрашивать, что играет. */
    public static void tick() {
        WebEngine.View v = view;
        if (v == null) {
            return;
        }
        v.keepAlive();
        long now = System.currentTimeMillis();
        if (v.ready() && now - lastPoll > 1000) {
            lastPoll = now;
            v.eval(POLL);
        }
    }

    private static void onConsole(String message) {
        if (message.startsWith("__bpsc-probe:")) {
            BlockPhoneFabric.LOGGER.info("[SoundCloud] {}", message); // проверка DevShots
            return;
        }
        if (!message.startsWith(MARK)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(message.substring(MARK.length())).getAsJsonObject();
            String art = o.get("art").getAsString();
            Playing p = new Playing(o.get("t").getAsString(), o.get("a").getAsString(), art.isEmpty() ? null : art,
                    Math.round(o.get("pos").getAsDouble() * 1000), Math.round(o.get("dur").getAsDouble() * 1000),
                    o.get("p").getAsBoolean());
            playing = p;
            lastState = System.currentTimeMillis();
            // Заиграло на сайте — плеер телефона замолкает (два трека сразу не нужны).
            if (p.playing() && !wasPlaying && MusicPlayer.state() == MusicPlayer.State.PLAYING) {
                MusicPlayer.pause();
            }
            wasPlaying = p.playing();
        } catch (RuntimeException e) {
            BlockPhoneFabric.LOGGER.debug("SoundCloud state: {}", e.toString());
        }
    }

    // --- Управление (кнопки плеера сайта) ---

    public static void toggle() {
        click(".playControl");
    }

    public static void pause() {
        Playing p = playing();
        if (p != null && p.playing()) {
            toggle();
        }
    }

    public static void next() {
        click(".skipControl__next");
    }

    public static void previous() {
        click(".skipControl__previous");
    }

    private static void click(String selector) {
        WebEngine.View v = view;
        if (v != null && v.ready()) {
            v.eval("(function(){var b=document.querySelector('" + selector + "');if(b)b.click();})();");
            lastPoll = 0;
        }
    }

    /** На главную SoundCloud. */
    public static void home() {
        view().load(HOME);
    }

    private SoundCloudWeb() {
    }
}
