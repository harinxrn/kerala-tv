package com.hari.indiatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * IndiaTV v2 - Malayalam only.
 * Two sections: "YT News" (startup; YouTube Live per channel, self-healing)
 * and "TV" (Malayalam IPTV channels only, from iptv-org, allowlist-filtered).
 */
public class MainActivity extends Activity {

    private static final String[] SOURCES = {
            "https://iptv-org.github.io/iptv/countries/in.m3u",
            "https://iptv-org.github.io/iptv/languages/mal.m3u"
    };

    public static final String SECTION_NEWS = "YT News";
    public static final String SECTION_TV = "TV";

    // YT News tiles, in the order Hari sent the links (Big TV 10th, Jaihind 11th).
    private static final String[] CURATED_NEWS = {
            "Asianet News", "Manorama News", "Mathrubhumi News", "24 News", "Reporter TV",
            "Media One", "News18 Kerala", "Kairali News", "Janam TV", "Big TV 24x7",
            "Jaihind TV"
    };

    // Verified 2026-10-23 via YouTube oEmbed from Hari's own links
    // (see yt-news-channels.md). Store ONLY the handle /live URL.
    private static final Map<String, String> DEFAULT_YT = new HashMap<String, String>();
    static {
        DEFAULT_YT.put("asianet news", "https://www.youtube.com/@asianetnews/live");
        DEFAULT_YT.put("manorama news", "https://www.youtube.com/@manoramanews/live");
        DEFAULT_YT.put("mathrubhumi news", "https://www.youtube.com/@TVMathrubhumi/live");
        DEFAULT_YT.put("24 news", "https://www.youtube.com/@24OnLive/live");
        DEFAULT_YT.put("reporter tv", "https://www.youtube.com/@reporterlive/live");
        DEFAULT_YT.put("media one", "https://www.youtube.com/@MediaoneTVLive/live");
        DEFAULT_YT.put("news18 kerala", "https://www.youtube.com/@News18Kerala/live");
        DEFAULT_YT.put("kairali news", "https://www.youtube.com/@kairalinews/live");
        DEFAULT_YT.put("janam tv", "https://www.youtube.com/@tvjanam/live");
        DEFAULT_YT.put("big tv 24x7", "https://www.youtube.com/@bigtv24x7live/live");
        DEFAULT_YT.put("jaihind tv", "https://www.youtube.com/@JaihindNewslive/live");
    }

    // The only TV channels v2 keeps: true Malayalam channels present in
    // iptv-org (in.m3u / mal.m3u, checked fresh 2026-10-23). Everything else
    // in those playlists (other languages, or Malayalam-audio tracks of
    // pan-India / foreign channels) is dropped.
    private static final Set<String> ALLOW = new HashSet<String>();
    static {
        String[] a = {
                "24 news", "amrita tv", "anand tv", "asianet", "asianet hd", "asianet middle east",
                "asianet movies", "asianet movies hd", "asianet news", "big tv 24x7", "c malayalam tv",
                "darshana tv", "dd malayalam", "flowers tv", "flowers tv usa", "garshom tv",
                "goodness tv", "harvest tv", "harvest tv keralam", "jaihind tv",
                "janam tv", "jeevan tv", "kairali arabia", "kairali news", "kairali tv",
                "kairali we", "kalika tv", "kappa tv", "kaumudy tv", "kerala vision",
                "kite victers", "kcl tv", "magnavision tv", "manorama news",
                "mathrubhumi news", "mazhavil manorama", "mazhavil manorama hd", "media one", "news18 kerala",
                "powervision tv", "pravasi channel", "pulari tv", "raj musix malayalam",
                "raj news malayalam", "real news kerala", "reporter tv", "safari tv",
                "shalom", "shekinah tv", "sun surya", "sun surya hd", "surya tv", "utv palakkad"
        };
        for (String s : a) ALLOW.add(s);
    }

    private GridView grid;
    private TextView status;
    private TextView countLabel;
    private TextView sectionSub;
    private TextView tabNews;
    private TextView tabTv;
    private ProgressBar loading;
    private ChannelAdapter adapter;
    private SharedPreferences prefs;

    private List<Channel> tvList = new ArrayList<Channel>();
    private List<Channel> newsTiles = new ArrayList<Channel>();
    private String currentSection = SECTION_NEWS;
    private View lastScaled;
    private boolean busy = false;
    private boolean liveCheckRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("ytlinks", MODE_PRIVATE);
        grid = (GridView) findViewById(R.id.grid);
        status = (TextView) findViewById(R.id.status);
        countLabel = (TextView) findViewById(R.id.countLabel);
        sectionSub = (TextView) findViewById(R.id.sectionSub);
        tabNews = (TextView) findViewById(R.id.tabNews);
        tabTv = (TextView) findViewById(R.id.tabTv);
        loading = (ProgressBar) findViewById(R.id.loading);
        adapter = new ChannelAdapter();
        grid.setAdapter(adapter);

        setupTab(tabNews, SECTION_NEWS);
        setupTab(tabTv, SECTION_TV);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                List<Channel> list = adapter.data;
                if (list == null || position >= list.size()) return;
                Channel c = list.get(position);
                if (SECTION_NEWS.equals(currentSection)) {
                    String yt = effectiveYt(c);
                    if (yt.length() > 0) {
                        playYouTube(c, yt, list, position);
                        return;
                    }
                }
                playIptv(list, position);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (!SECTION_NEWS.equals(currentSection)) return false;
                List<Channel> list = adapter.data;
                if (list == null || position >= list.size()) return false;
                showNewsMenu(list.get(position), position);
                return true;
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                scaleCard(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    View sel = grid.getChildAt(grid.getSelectedItemPosition() - grid.getFirstVisiblePosition());
                    if (sel != null) scaleCard(sel);
                }
            }
        });
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && grid.getSelectedItemPosition() < 4) {
                    (SECTION_NEWS.equals(currentSection) ? tabNews : tabTv).requestFocus();
                    return true;
                }
                return false;
            }
        });

        TextView refresh = (TextView) findViewById(R.id.refreshBtn);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadData(true);
            }
        });
        refresh.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    grid.requestFocus();
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    tabTv.requestFocus();
                    return true;
                }
                return false;
            }
        });

        loadData(false);
    }

    private void scaleCard(View view) {
        if (view == null) return;
        if (lastScaled != null && lastScaled != view) {
            lastScaled.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        }
        view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(150).start();
        lastScaled = view;
    }

    // ---------- tabs (the only two sections) ----------

    private void setupTab(final TextView t, final String section) {
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectSection(section);
                grid.requestFocus();
            }
        });
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) selectSection(section);
                updateTabs();
            }
        });
        t.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    grid.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && t == tabNews) {
                    tabTv.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && t == tabTv) {
                    tabNews.requestFocus();
                    return true;
                }
                return false;
            }
        });
    }

    private void styleTab(TextView t, boolean selected, boolean focused) {
        if (selected) {
            t.setBackgroundResource(R.drawable.tab_selected);
            t.setTextColor(0xFF0B0B0F);
        } else if (focused) {
            t.setBackgroundResource(R.drawable.tab_focused);
            t.setTextColor(0xFFFFFFFF);
        } else {
            t.setBackgroundResource(R.drawable.tab_plain);
            t.setTextColor(0xFFC9CAD1);
        }
        t.setScaleX(selected || focused ? 1.04f : 1f);
        t.setScaleY(selected || focused ? 1.04f : 1f);
    }

    private void updateTabs() {
        styleTab(tabNews, SECTION_NEWS.equals(currentSection), tabNews.hasFocus());
        styleTab(tabTv, SECTION_TV.equals(currentSection), tabTv.hasFocus());
    }

    private void selectSection(String section) {
        if (section.equals(currentSection) && adapter.data != null && adapter.data.size() > 0
                && ((SECTION_NEWS.equals(section) && adapter.data == newsTiles)
                || (SECTION_TV.equals(section) && adapter.data == tvList))) {
            updateTabs();
            return;
        }
        currentSection = section;
        if (lastScaled != null) {
            lastScaled.setScaleX(1f);
            lastScaled.setScaleY(1f);
            lastScaled = null;
        }
        List<Channel> list = SECTION_NEWS.equals(section) ? newsTiles : tvList;
        adapter.setData(list);
        if (list.size() > 0) grid.setSelection(0);
        if (SECTION_NEWS.equals(section)) {
            countLabel.setText(list.size() + " channels");
            sectionSub.setText("Live news on YouTube - checked fresh every open. Hold OK for IPTV / link options.");
        } else {
            countLabel.setText(list.size() + " channels");
            sectionSub.setText("Malayalam channels - live TV streams.");
        }
        updateTabs();
    }

    // ---------- naming / quality ----------

    static String baseName(String name) {
        int p = name.indexOf(" (");
        String b = p > 0 ? name.substring(0, p) : name;
        int b2 = b.indexOf(" [");
        if (b2 > 0) b = b.substring(0, b2);
        return b.trim();
    }

    /** Dedupe key: base name without a trailing HD marker (Mazhavil Manorama vs HD are one channel). */
    static String dedupeKey(String name) {
        String b = baseName(name).toLowerCase();
        if (b.endsWith(" hd")) b = b.substring(0, b.length() - 3);
        return b;
    }

    static int qualityScore(Channel c) {
        String u = c.name.toUpperCase();
        int score = 0;
        if (u.contains("2160")) score = 400;
        else if (u.contains("1440")) score = 300;
        else if (u.contains("1080")) score = 200;
        else if (u.contains("720")) score = 100;
        else if (u.contains("576")) score = 60;
        else if (u.contains("504")) score = 50;
        else if (u.contains("480")) score = 40;
        else if (u.contains("396") || u.contains("360")) score = 30;
        if (u.contains("HD")) score += 10;
        return score;
    }

    static String qualityLabel(Channel c) {
        String u = c.name.toUpperCase();
        if (u.contains("1080")) return "1080p";
        if (u.contains("720")) return "720p";
        if (u.contains("576")) return "576p";
        if (u.contains("504")) return "504p";
        if (u.contains("480")) return "480p";
        if (u.contains("HD")) return "HD";
        return "SD";
    }

    private void playIptv(List<Channel> list, int position) {
        Channel c = list.get(position);
        if (c.url == null || c.url.length() == 0) {
            Toast.makeText(this, baseName(c.name) + " has no TV stream - YouTube only", Toast.LENGTH_LONG).show();
            return;
        }
        AppData.youtubeMode = false;
        AppData.playList = list;
        AppData.playIndex = position;
        AppData.playTitle = currentSection;
        startActivity(new Intent(MainActivity.this, PlayerActivity.class));
    }

    /**
     * v2.6: clone the news tiles into a YouTube playback queue, in home-grid
     * order, with each tile's effective /live URL filled in. The players use
     * it to zap between news channels with UP / DOWN without coming back to
     * the grid. url keeps the tile's IPTV stream - the fallback.
     */
    private List<Channel> buildYtQueue(List<Channel> tiles) {
        List<Channel> queue = new ArrayList<Channel>();
        for (Channel c : tiles) {
            Channel q = new Channel();
            q.name = c.name;
            q.url = c.url;
            q.logo = c.logo;
            q.groups.addAll(c.groups);
            q.headers.putAll(c.headers);
            q.ytLiveUrl = effectiveYt(c);
            queue.add(q);
        }
        return queue;
    }

    // ---------- data loading ----------

    private void loadData(final boolean force) {
        if (busy) return;
        busy = true;
        if (!force) {
            List<Channel> cached = readCache();
            if (cached != null && cached.size() > 0) {
                busy = false;
                applyData(cached);
                return;
            }
        }
        loading.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText("Loading Malayalam channels...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Channel> merged = fetchAll();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        loading.setVisibility(View.GONE);
                        if (merged != null && merged.size() > 0) {
                            writeCache(merged);
                            status.setVisibility(View.GONE);
                            applyData(merged);
                        } else if (tvList.size() > 0 || newsTiles.size() > 0) {
                            status.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, "Refresh failed - showing saved channels", Toast.LENGTH_LONG).show();
                        } else {
                            status.setText("Could not load channels. Check the TV internet, then press Refresh.");
                        }
                    }
                });
            }
        }).start();
    }

    private List<Channel> fetchAll() {
        LinkedHashMap<String, Channel> byUrl = new LinkedHashMap<String, Channel>();
        for (int s = 0; s < SOURCES.length; s++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(SOURCES[s]).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
                InputStream in = conn.getInputStream();
                List<Channel> list = M3uParser.parse(in);
                in.close();
                conn.disconnect();
                for (Channel c : list) {
                    c.malayalam = true;
                    c.recomputeFlags();
                    Channel existing = byUrl.get(c.url);
                    if (existing == null) byUrl.put(c.url, c);
                    else existing.mergeFrom(c);
                }
            } catch (Exception e) {
                // one bad source should not sink the rest
            }
        }
        if (byUrl.isEmpty()) return null;
        return new ArrayList<Channel>(byUrl.values());
    }

    // ---------- cache (v2 file, RAW merged list) ----------

    private File cacheFile() {
        return new File(getFilesDir(), "channels-v2.tsv");
    }

    private List<Channel> readCache() {
        try {
            File f = cacheFile();
            if (!f.exists()) return null;
            if (System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) return null;
            List<Channel> out = new ArrayList<Channel>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 6) continue;
                Channel c = new Channel();
                c.name = URLDecoder.decode(p[0], "UTF-8");
                c.url = p[1];
                c.logo = p[2];
                if (p[3].length() > 0) {
                    for (String g : p[3].split(";")) c.groups.add(g);
                }
                c.malayalam = true;
                if (p.length > 6 && p[6].length() > 0) {
                    for (String kv : p[6].split("&")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0) c.headers.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                    }
                }
                c.recomputeFlags();
                out.add(c);
            }
            r.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(final List<Channel> list) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(cacheFile()), "UTF-8");
                    for (Channel c : list) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(URLEncoder.encode(c.name, "UTF-8")).append('\t');
                        sb.append(c.url).append('\t');
                        sb.append(c.logo).append('\t');
                        for (int i = 0; i < c.groups.size(); i++) {
                            if (i > 0) sb.append(';');
                            sb.append(c.groups.get(i));
                        }
                        sb.append('\t').append('0').append('\t').append('1').append('\t');
                        boolean first = true;
                        for (Map.Entry<String, String> e : c.headers.entrySet()) {
                            if (!first) sb.append('&');
                            first = false;
                            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
                        }
                        sb.append('\n');
                        w.write(sb.toString());
                    }
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ---------- build the two sections ----------

    private void applyData(List<Channel> raw) {
        // TV: allowlist only, deduped by channel, best quality wins.
        LinkedHashMap<String, Channel> bestTv = new LinkedHashMap<String, Channel>();
        for (Channel c : raw) {
            if (!ALLOW.contains(baseName(c.name).toLowerCase())) continue;
            String key = dedupeKey(c.name);
            Channel prev = bestTv.get(key);
            if (prev == null || qualityScore(c) > qualityScore(prev)) bestTv.put(key, c);
        }
        tvList = new ArrayList<Channel>(bestTv.values());
        Collections.sort(tvList, new Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                return baseName(a.name).compareToIgnoreCase(baseName(b.name));
            }
        });

        // YT News: curated order; IPTV stream (if any) is kept as the fallback.
        newsTiles = new ArrayList<Channel>();
        for (String name : CURATED_NEWS) {
            Channel match = bestTv.get(dedupeKey(name));
            if (match != null) {
                newsTiles.add(match);
            } else {
                Channel synthetic = new Channel();
                synthetic.name = name;
                synthetic.url = "";
                synthetic.logo = "";
                newsTiles.add(synthetic);
            }
        }

        currentSection = "";
        adapter.data = new ArrayList<Channel>();
        selectSection(SECTION_NEWS);
        tabNews.requestFocus();
        refreshLiveStatus();
    }

    // ---------- YouTube self-healing ----------

    private String ytKey(Channel c) {
        return "yt_" + baseName(c.name).toLowerCase();
    }

    /** Saved (normalized) channel link wins; "" means cleared; else the verified default handle. */
    private String effectiveYt(Channel c) {
        if (prefs.contains(ytKey(c))) return prefs.getString(ytKey(c), "");
        String d = DEFAULT_YT.get(baseName(c.name).toLowerCase());
        return d == null ? "" : d;
    }

    /**
     * On every app open: for each news channel, fetch its /live page fresh,
     * save the CURRENT video id, and mark the tile LIVE / not-live. A stream
     * that ended since yesterday is replaced here automatically - the stored
     * handle never changes, only the resolved id does.
     */
    private void refreshLiveStatus() {
        if (liveCheckRunning) return;
        liveCheckRunning = true;
        final List<Channel> tiles = new ArrayList<Channel>(newsTiles);
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (Channel c : tiles) {
                    String yt = effectiveYt(c);
                    if (yt.length() == 0 || yt.endsWith(".m3u8")) continue;
                    String vid = YtResolver.liveVideoId(yt);
                    SharedPreferences.Editor e = prefs.edit();
                    e.putString("vid_" + baseName(c.name).toLowerCase(), vid == null ? "" : vid);
                    e.putBoolean("live_" + baseName(c.name).toLowerCase(), vid != null);
                    e.putLong("checked_" + baseName(c.name).toLowerCase(), System.currentTimeMillis());
                    e.apply();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (SECTION_NEWS.equals(currentSection)) adapter.notifyDataSetChanged();
                        }
                    });
                }
                liveCheckRunning = false;
            }
        }).start();
    }

    private void playYouTube(final Channel c, final String liveUrl, final List<Channel> iptvList, final int iptvPos) {
        // v2.6: hand both players the whole news queue, not just this one
        // channel, so UP / DOWN can switch channels during playback.
        final List<Channel> queue = buildYtQueue(iptvList);
        AppData.ytQueue = queue;
        AppData.ytIndex = iptvPos;
        AppData.youtubeMode = true;
        Toast.makeText(this, "Finding the latest live for " + baseName(c.name) + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Fresh resolution on every tap - never a cached video id / HLS.
                String m3u8 = YtResolver.resolveHls(liveUrl);
                if (m3u8 != null && !YtResolver.isPlayableHls(m3u8)) {
                    // Manifest was stale (stream restarted): resolve once more from scratch.
                    m3u8 = YtResolver.resolveHls(liveUrl);
                    if (m3u8 != null && !YtResolver.isPlayableHls(m3u8)) m3u8 = null;
                }
                if (m3u8 != null) {
                    final String hls = m3u8;
                    // Remember the id that actually worked, for the tile badge.
                    String vid = YtResolver.liveVideoId(liveUrl);
                    if (vid != null) {
                        prefs.edit().putString("vid_" + baseName(c.name).toLowerCase(), vid)
                                .putBoolean("live_" + baseName(c.name).toLowerCase(), true).apply();
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Native player, YouTube mode: it walks the
                            // queue and resolves each channel on switch;
                            // this first channel's stream is already here.
                            queue.get(iptvPos).ytResolvedUrl = hls;
                            AppData.ytQueue = queue;
                            AppData.ytIndex = iptvPos;
                            AppData.youtubeMode = true;
                            AppData.playTitle = SECTION_NEWS;
                            startActivity(new Intent(MainActivity.this, PlayerActivity.class));
                        }
                    });
                    return;
                }
                // Embed fallback still needs a CURRENT id, so fetch it fresh too.
                final String videoId = YtResolver.liveVideoId(liveUrl);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (videoId != null) {
                            // Embed player: AppData.playList stays the queue
                            // itself - its entries carry the IPTV fallbacks
                            // the failure dialog / age-gate switch point at.
                            AppData.playList = queue;
                            AppData.playIndex = iptvPos;
                            AppData.playTitle = currentSection;
                            Intent i = new Intent(MainActivity.this, WebPlayerActivity.class);
                            i.putExtra(WebPlayerActivity.EXTRA_VIDEO_ID, videoId);
                            i.putExtra(WebPlayerActivity.EXTRA_TITLE, baseName(c.name) + " - YouTube Live");
                            i.putExtra(WebPlayerActivity.EXTRA_ORIGINAL_URL, liveUrl);
                            startActivity(i);
                        } else if (c.url != null && c.url.length() > 0) {
                            Toast.makeText(MainActivity.this, "YouTube live is offline - playing the TV stream", Toast.LENGTH_LONG).show();
                            playIptv(iptvList, iptvPos);
                        } else {
                            openInYouTubeApp(liveUrl);
                        }
                    }
                });
            }
        }).start();
    }

    private void showNewsMenu(final Channel c, final int position) {
        final String yt = effectiveYt(c);
        final List<String> labels = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();
        labels.add("Play YouTube Live (find latest)"); actions.add(0);
        labels.add("Play TV stream"); actions.add(1);
        labels.add((yt.length() > 0 ? "Edit" : "Add") + " YouTube channel link"); actions.add(2);
        if (yt.length() > 0) { labels.add("Reset YouTube link to default"); actions.add(3); }
        String[] items = labels.toArray(new String[labels.size()]);
        new AlertDialog.Builder(this)
                .setTitle(baseName(c.name))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int act = actions.get(which);
                        if (act == 0) {
                            if (yt.length() == 0) {
                                Toast.makeText(MainActivity.this, "No YouTube link yet - add one first", Toast.LENGTH_LONG).show();
                                editYtLink(c);
                            } else {
                                playYouTube(c, yt, adapter.data, position);
                            }
                        } else if (act == 1) {
                            playIptv(adapter.data, position);
                        } else if (act == 2) {
                            editYtLink(c);
                        } else if (act == 3) {
                            prefs.edit().remove(ytKey(c)).apply();
                            adapter.notifyDataSetChanged();
                            refreshLiveStatus();
                            Toast.makeText(MainActivity.this, "Reset to the built-in channel link", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void editYtLink(final Channel c) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(effectiveYt(c));
        input.setSelection(input.getText().length());
        input.setHint("https://www.youtube.com/@channel/live");
        new AlertDialog.Builder(this)
                .setTitle("YouTube channel - " + baseName(c.name))
                .setMessage("Paste the CHANNEL page or its /live page (with the @handle). The app finds the current live itself, so a channel link never expires. A single video link works only until that stream ends.")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String val = YtResolver.normalizeToLiveUrl(input.getText().toString());
                        prefs.edit().putString(ytKey(c), val).apply();
                        adapter.notifyDataSetChanged();
                        refreshLiveStatus();
                        Toast.makeText(MainActivity.this,
                                val.length() > 0 ? "Saved: " + val : "Cleared. This tile now plays the TV stream.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openInYouTubeApp(String link) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            i.setPackage("com.google.android.youtube.tv");
            startActivity(i);
        } catch (ActivityNotFoundException e1) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Could not open that YouTube link - check it, or use the TV stream", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- grid adapter ----------

    private class ChannelAdapter extends BaseAdapter {
        List<Channel> data = new ArrayList<Channel>();

        void setData(List<Channel> d) {
            data = d;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_channel, parent, false);
            }
            Channel c = data.get(position);
            ImageView logo = (ImageView) v.findViewById(R.id.logo);
            TextView name = (TextView) v.findViewById(R.id.name);
            TextView badge = (TextView) v.findViewById(R.id.badge);
            name.setText(baseName(c.name));
            if (SECTION_NEWS.equals(currentSection)) {
                String key = baseName(c.name).toLowerCase();
                boolean checked = prefs.contains("live_" + key);
                boolean live = prefs.getBoolean("live_" + key, false);
                if (checked && live) {
                    badge.setText("\u25CF LIVE  -  YouTube");
                    badge.setTextColor(0xFFFF453A);
                } else if (checked) {
                    badge.setText("YouTube  -  checking / offline");
                    badge.setTextColor(0xFF9A9AA3);
                } else {
                    badge.setText("YouTube Live");
                    badge.setTextColor(0xFF9A9AA3);
                }
            } else {
                String g = c.groups.size() > 0 ? c.groups.get(0) : "";
                badge.setText(g.length() > 0 ? qualityLabel(c) + "  -  " + g : qualityLabel(c));
                badge.setTextColor(0xFF9A9AA3);
            }
            ImageLoader.load(logo, c.logo);
            return v;
        }
    }
}
