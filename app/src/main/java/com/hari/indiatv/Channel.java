package com.hari.indiatv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Channel {
    public String name = "";
    public String url = "";
    public String logo = "";
    public List<String> groups = new ArrayList<String>();
    public Map<String, String> headers = new LinkedHashMap<String, String>();
    public boolean india;
    public boolean malayalam;
    public boolean sports;
    public boolean movies;
    /** For YouTube playback: the channel's permanent /live page (handle URL).
     *  Never a one-off video link - the current video id is resolved fresh. */
    public String ytLiveUrl;
    /** Transient: the HLS manifest most recently resolved for ytLiveUrl.
     *  Short-lived by nature - re-resolved whenever playback (re)starts. */
    public String ytResolvedUrl;

    public void recomputeFlags() {
        for (String g : groups) {
            if (g.equalsIgnoreCase("Sports")) sports = true;
            if (g.equalsIgnoreCase("Movies")) movies = true;
        }
    }

    public void mergeFrom(Channel other) {
        for (String g : other.groups) {
            if (!groups.contains(g)) groups.add(g);
        }
        for (Map.Entry<String, String> e : other.headers.entrySet()) {
            if (!headers.containsKey(e.getKey())) headers.put(e.getKey(), e.getValue());
        }
        india = india || other.india;
        malayalam = malayalam || other.malayalam;
        recomputeFlags();
    }
}
