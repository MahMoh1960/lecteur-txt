package ma.lecteurtxt;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Liste des fichiers récents et position de lecture, stockées dans SharedPreferences. */
final class RecentStore {

    static final class Item {
        String uri;
        String name;
        long time;
        int offset;
    }

    private static final int MAX = 30;
    private static final String KEY = "recents";
    private final SharedPreferences sp;

    RecentStore(SharedPreferences sp) {
        this.sp = sp;
    }

    List<Item> load() {
        List<Item> out = new ArrayList<>();
        String raw = sp.getString(KEY, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 4) {
                continue;
            }
            try {
                Item it = new Item();
                it.uri = f[0];
                it.name = f[1];
                it.time = Long.parseLong(f[2]);
                it.offset = Integer.parseInt(f[3]);
                out.add(it);
            } catch (NumberFormatException ignored) {
                // ligne ignorée
            }
        }
        return out;
    }

    private void save(List<Item> l) {
        StringBuilder sb = new StringBuilder();
        for (Item it : l) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(clean(it.uri)).append('\t').append(clean(it.name)).append('\t')
                    .append(it.time).append('\t').append(it.offset);
        }
        sp.edit().putString(KEY, sb.toString()).apply();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    void touch(String uri, String name, int offset) {
        List<Item> l = load();
        Item found = null;
        for (Item it : l) {
            if (it.uri.equals(uri)) {
                found = it;
                break;
            }
        }
        if (found != null) {
            l.remove(found);
        } else {
            found = new Item();
        }
        found.uri = uri;
        found.name = name;
        found.time = System.currentTimeMillis();
        found.offset = offset;
        l.add(0, found);
        while (l.size() > MAX) {
            l.remove(l.size() - 1);
        }
        save(l);
    }

    void setOffset(String uri, int offset) {
        List<Item> l = load();
        for (Item it : l) {
            if (it.uri.equals(uri)) {
                it.offset = offset;
                save(l);
                return;
            }
        }
    }

    int offsetOf(String uri) {
        for (Item it : load()) {
            if (it.uri.equals(uri)) {
                return it.offset;
            }
        }
        return 0;
    }

    void remove(String uri) {
        List<Item> l = load();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).uri.equals(uri)) {
                l.remove(i);
                break;
            }
        }
        save(l);
    }
}
