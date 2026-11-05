package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Supplier;

/** Список треков Яндекса по запросу (чарт и т. п.): нажатие — играть с этого трека; в заголовке — «Играть всё». */
final class YandexTracksView extends MusicView {
    private final String title;
    private final Supplier<List<Track>> tracks;

    YandexTracksView(MusicApp app, String title, Supplier<List<Track>> tracks) {
        super(app);
        this.title = title;
        this.tracks = tracks;
    }

    @Override
    String title() {
        return title;
    }

    @Override
    String headerAction() {
        List<Track> list = tracks.get();
        return list != null && !list.isEmpty() ? tr("music.play_all") : null;
    }

    @Override
    void onHeaderAction() {
        List<Track> list = tracks.get();
        if (list != null && !list.isEmpty()) {
            MusicPlayer.play(list, 0, null, title);
        }
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        List<Track> list = tracks.get();
        if (list == null) {
            hint(g, tr("music.loading"), height() / 2 - 10);
            return;
        }
        clampScroll(list.size() * TrackRows.H + 4, height());
        beginList(g, 0, height());
        TrackRows.list(this, g, list, 2 - (int) scroll, width(), mouseX, mouseY, title);
        endList(g);
    }
}
