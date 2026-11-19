package com.suiji.note;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简本地存储：SharedPreferences + JSON（沿用旧版字段，升级不丢数据）。
 * 任何改动都立刻落盘，保证「下次打开文字还在」。
 */
public class NoteStore {

    public static class Note {
        public String text = "";
        public long updated = 0L;

        public Note() {
        }

        public Note(String text, long updated) {
            this.text = text == null ? "" : text;
            this.updated = updated;
        }
    }

    private static final String SP_NAME = "suiji_store";
    private static final String KEY_DATA = "notes";
    private static final String KEY_INDEX = "index";

    private final SharedPreferences sp;
    private final List<Note> notes = new ArrayList<>();
    private int index = 0;

    public NoteStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        String raw = null;
        try {
            raw = sp.getString(KEY_DATA, null);
        } catch (Exception ignored) {
        }
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    notes.add(new Note(o.optString("t", ""), o.optLong("u", 0L)));
                }
            } catch (Exception ignored) {
            }
        }
        if (notes.isEmpty()) notes.add(new Note());
        index = sp.getInt(KEY_INDEX, 0);
        if (index >= notes.size()) index = notes.size() - 1;
        if (index < 0) index = 0;
    }

    public List<Note> notes() {
        return notes;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int i) {
        index = Math.max(0, Math.min(notes.size() - 1, i));
    }

    public int size() {
        return notes.size();
    }

    public Note current() {
        return notes.get(index);
    }

    public static boolean isBlank(Note n) {
        return n == null || n.text == null || n.text.trim().length() == 0;
    }

    public boolean currentBlank() {
        return isBlank(current());
    }

    /** 新建一张空卡片（追加到末尾）。 */
    public Note addNote() {
        Note n = new Note("", System.currentTimeMillis());
        notes.add(n);
        return n;
    }

    /** 删除指定卡片；删除后索引自动收敛到合理位置。 */
    public void removeAt(int i) {
        if (i < 0 || i >= notes.size()) return;
        if (notes.size() <= 1) return;
        notes.remove(i);
        if (index > i) index--;
        if (index >= notes.size()) index = notes.size() - 1;
        if (index < 0) index = 0;
    }

    public boolean isEmpty() {
        for (Note n : notes) {
            if (!isBlank(n)) return false;
        }
        return true;
    }

    public void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Note n : notes) {
                JSONObject o = new JSONObject();
                o.put("t", n.text == null ? "" : n.text);
                o.put("u", n.updated);
                arr.put(o);
            }
            sp.edit().putString(KEY_DATA, arr.toString()).putInt(KEY_INDEX, index).apply();
        } catch (Exception ignored) {
        }
    }
}
