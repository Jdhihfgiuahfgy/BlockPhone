package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Очередь воспроизведения: клик — перейти к треку. Сразу прокручена к текущему. */
final class QueueView extends MusicView {
    private boolean positioned;

    QueueView(MusicApp app) {
        super(app);
    }

    @Override
    String title() {
        return tr("music.queue");
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        List<Track> queue = MusicPlayer.queue();
        if (queue.isEmpty()) {
            hint(g, tr("music.nothing_playing"), height() / 2 - 10);
            return;
        }
        if (!positioned) {
            scroll = Math.max(0, MusicPlayer.index() * TrackRows.H - TrackRows.H);
            positioned = true;
        }
        clampScroll(queue.size() * TrackRows.H + 4, height());
        beginList(g, 0, height());
        int y = 2 - (int) scroll;
        for (int i = 0; i < queue.size(); i++) {
            int index = i;
            if (y + TrackRows.H >= 0 && y <= height()) {
                TrackRows.draw(this, g, queue.get(i), y, width(), mouseX, mouseY, () -> MusicPlayer.jump(index));
            }
            y += TrackRows.H;
        }
        endList(g);
    }
}
