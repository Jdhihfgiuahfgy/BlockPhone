package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.LocalLibrary;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Util;

import java.util.List;

/** Вкладка «Моя музыка»: файлы из папки blockphone/music, «Слушать все» и «Перемешать», «Папка» — открыть её в проводнике. */
final class LocalView extends MusicView {
    private static final int LIST_TOP = 24;

    LocalView(MusicApp app) {
        super(app);
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return tr("app.music");
    }

    @Override
    String headerAction() {
        return tr("music.folder");
    }

    @Override
    void onHeaderAction() {
        LocalLibrary.rescan();
        Util.getPlatform().openPath(LocalLibrary.dir());
    }

    @Override
    void onShown() {
        LocalLibrary.rescan();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        List<Track> tracks = LocalLibrary.tracks();
        if (!LocalLibrary.scanned()) {
            hint(g, tr("music.scanning"), height() / 2 - 10);
            return;
        }
        if (tracks.isEmpty()) {
            hint(g, tr("music.local_empty"), height() / 2 - 40);
            button(g, w / 2 - 50, height() / 2 + 12, 100, 16, tr("music.open_folder"), mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY,
                    this::onHeaderAction);
            button(g, w / 2 - 50, height() / 2 + 32, 100, 16, tr("music.rescan"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                    LocalLibrary::rescan);
            return;
        }
        int half = (w - 12) / 2;
        button(g, 4, 4, half, 15, "▶ " + tr("music.play_all"), mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY, () -> {
            MusicPlayer.setShuffle(false);
            MusicPlayer.play(tracks, 0, null, tr("music.tab.local"));
        });
        button(g, 8 + half, 4, half, 15, tr("music.shuffle_all"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> {
            MusicPlayer.setShuffle(true);
            MusicPlayer.play(tracks, (int) (Math.random() * tracks.size()), null, tr("music.tab.local"));
        });

        clampScroll(tracks.size() * TrackRows.H + 16, height() - LIST_TOP);
        int listMouseY = mouseY >= LIST_TOP ? mouseY : -1000;
        beginList(g, LIST_TOP, height());
        int y = TrackRows.list(this, g, tracks, LIST_TOP - (int) scroll, w, mouseX, listMouseY, tr("music.tab.local"));
        PhoneDraw.centeredText(g, tr("music.tracks_count", tracks.size()), w / 2f, y + 4, PhoneTheme.secondaryText(), 0.6f, false);
        endList(g);
    }
}
