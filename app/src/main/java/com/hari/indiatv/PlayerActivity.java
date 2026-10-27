package com.hari.indiatv;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 player: clean Apple-TV-style bottom panel (logo, LIVE badge, channel
 * name, position, hints) that fades out, plus YouTube self-healing - if a
 * YouTube HLS stream dies mid-play, the channel's latest live is resolved
 * fresh and playback resumes instead of skipping / going dead.
 */
public class PlayerActivity extends Activity {

    private List<Channel> list;
    private int index;
    private MediaPlayer mp;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private ProgressBar buffering;
    private TextView bufferingLabel;
    private View panel;
    private TextView nowPlaying;
    private TextView subInfo;
    private ImageView channelLogo;
    private Handler handler = new Handler();
    private int errorStreak = 0;
    private int ytRetry = 0;
    private boolean stopped = false;
    private boolean paused = false;
    private boolean resolving = false;
    // v2.6: YouTube queue mode - list is AppData.ytQueue and each entry's
    // stream is resolved fresh when zapped to, instead of being a fixed URL.
    private boolean ytMode = false;
    private boolean currentIptvFallback = false;
    private int playGeneration = 0;

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            if (!paused) panel.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        if (AppData.youtubeMode && AppData.ytQueue != null && !AppData.ytQueue.isEmpty()) {
            ytMode = true;
            list = AppData.ytQueue;
            index = AppData.ytIndex;
        } else {
            ytMode = false;
            list = AppData.playList;
            index = AppData.playIndex;
        }
        if (list == null || list.isEmpty()) {
            finish();
            return;
        }
        surface = (SurfaceView) findViewById(R.id.surface);
        buffering = (ProgressBar) findViewById(R.id.buffering);
        bufferingLabel = (TextView) findViewById(R.id.bufferingLabel);
        panel = findViewById(R.id.playerPanel);
        nowPlaying = (TextView) findViewById(R.id.nowPlaying);
        subInfo = (TextView) findViewById(R.id.subInfo);
        channelLogo = (ImageView) findViewById(R.id.channelLogo);
        holder = surface.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                play(index);
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                releasePlayer();
            }
        });
    }

    private Map<String, String> headersFor(Channel c) {
        Map<String, String> h = new HashMap<String, String>(c.headers);
        if (!h.containsKey("User-Agent")) {
            h.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        }
        return h;
    }

    private void showOverlay(long hideAfterMs) {
        final Channel c = list.get(index);
        nowPlaying.setText(displayName(c.name));
        String section = AppData.playTitle != null && AppData.playTitle.length() > 0 ? AppData.playTitle : "Live TV";
        String src = ytMode && !currentIptvFallback ? "YouTube Live" : "Live TV";
        subInfo.setText(section + "  -  " + src + "  -  " + (index + 1) + " of " + list.size());
        ImageLoader.load(channelLogo, c.logo);
        panel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        if (hideAfterMs > 0) handler.postDelayed(hideOverlay, hideAfterMs);
    }

    private static String displayName(String name) {
        int p = name.indexOf(" (");
        String b = p > 0 ? name.substring(0, p) : name;
        int b2 = b.indexOf(" [");
        if (b2 > 0) b = b.substring(0, b2);
        return b.trim();
    }

    private void play(int i) {
        if (list == null || list.isEmpty()) return;
        index = ((i % list.size()) + list.size()) % list.size();
        if (ytMode) AppData.ytIndex = index; else AppData.playIndex = index;
        final Channel c = list.get(index);
        final int gen = ++playGeneration;
        stopped = false;
        paused = false;
        resolving = false;
        currentIptvFallback = false;
        releasePlayer();
        showOverlay(4500);
        buffering.setVisibility(View.VISIBLE);
        bufferingLabel.setVisibility(View.VISIBLE);
        bufferingLabel.setText("Loading " + displayName(c.name) + "...");
        if (ytMode) {
            // YouTube queue: the stream URL is resolved per channel, fresh
            // on every zap - only the first channel arrives pre-resolved.
            if (c.ytResolvedUrl != null && c.ytResolvedUrl.length() > 0) {
                startNative(c, c.ytResolvedUrl);
            } else {
                resolveYouTube(c, gen, "Finding the latest live for " + displayName(c.name) + "...");
            }
            return;
        }
        startNative(c, c.url);
    }

    private void startNative(final Channel c, final String streamUrl) {
        if (streamUrl == null || streamUrl.length() == 0) {
            buffering.setVisibility(View.GONE);
            bufferingLabel.setVisibility(View.GONE);
            Toast.makeText(this, "Could not play " + displayName(c.name), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mp = new MediaPlayer();
            mp.setDisplay(holder);
            mp.setDataSource(getApplicationContext(), Uri.parse(streamUrl), headersFor(c));
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    errorStreak = 0;
                    ytRetry = 0;
                    buffering.setVisibility(View.GONE);
                    bufferingLabel.setVisibility(View.GONE);
                    player.start();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    buffering.setVisibility(View.GONE);
                    bufferingLabel.setVisibility(View.GONE);
                    // YouTube live HLS urls expire / rotate when a stream
                    // restarts: re-resolve the channel's latest live first.
                    if (ytMode && !currentIptvFallback && c.ytLiveUrl != null && ytRetry < 2 && !resolving) {
                        ytRetry++;
                        c.ytResolvedUrl = null;
                        resolveYouTube(c, playGeneration, "Live link changed - finding the new stream...");
                        return true;
                    }
                    errorStreak++;
                    if (list.size() <= 1 || errorStreak >= 12) {
                        nowPlaying.setText(displayName(c.name));
                        subInfo.setText("Stream unavailable right now. Back to go home, or Up / Down for another channel.");
                        panel.setVisibility(View.VISIBLE);
                        handler.removeCallbacks(hideOverlay);
                        return true;
                    }
                    Toast.makeText(PlayerActivity.this, "Could not play " + displayName(c.name) + " - skipping", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!stopped) play(index + 1);
                        }
                    }, 700);
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    // A live stream that "completes" has ended - for YouTube,
                    // that usually means a new live has already started.
                    if (ytMode && !currentIptvFallback && c.ytLiveUrl != null && ytRetry < 2 && !resolving) {
                        ytRetry++;
                        c.ytResolvedUrl = null;
                        resolveYouTube(c, playGeneration, "Live link changed - finding the new stream...");
                    }
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer player, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        buffering.setVisibility(View.VISIBLE);
                        bufferingLabel.setVisibility(View.VISIBLE);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        buffering.setVisibility(View.GONE);
                        bufferingLabel.setVisibility(View.GONE);
                    }
                    return false;
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer player, int width, int height) {
                    fitSurface(width, height);
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            buffering.setVisibility(View.GONE);
            bufferingLabel.setVisibility(View.GONE);
            Toast.makeText(this, "Could not play " + displayName(c.name), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Resolve one queue channel's current live and play it, in the app's
     * usual order: native HLS first, then the in-app embed player, then the
     * channel's IPTV stream. Used for the first channel, for every UP / DOWN
     * zap, and to self-heal when a stream dies or rotates mid-play.
     * gen guards against a slow resolve landing after the user zapped on.
     */
    private void resolveYouTube(final Channel c, final int gen, final String label) {
        resolving = true;
        buffering.setVisibility(View.VISIBLE);
        bufferingLabel.setVisibility(View.VISIBLE);
        bufferingLabel.setText(label);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String fresh = YtResolver.resolveHls(c.ytLiveUrl);
                if (fresh != null && !YtResolver.isPlayableHls(fresh)) {
                    // Manifest was stale (stream restarted): once more.
                    fresh = YtResolver.resolveHls(c.ytLiveUrl);
                    if (fresh != null && !YtResolver.isPlayableHls(fresh)) fresh = null;
                }
                final String hls = fresh;
                final String videoId = hls == null ? YtResolver.liveVideoId(c.ytLiveUrl) : null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != playGeneration || stopped || isFinishing()) return;
                        resolving = false;
                        if (hls != null) {
                            c.ytResolvedUrl = hls;
                            startNative(c, hls);
                        } else if (videoId != null) {
                            openWebPlayer(c, videoId);
                        } else if (c.url != null && c.url.length() > 0) {
                            currentIptvFallback = true;
                            showOverlay(4500);
                            Toast.makeText(PlayerActivity.this,
                                    "YouTube live is offline - playing the TV stream",
                                    Toast.LENGTH_LONG).show();
                            startNative(c, c.url);
                        } else {
                            nowPlaying.setText(displayName(c.name));
                            subInfo.setText("YouTube live is offline right now. Up / Down for another channel, or Back to go home.");
                            panel.setVisibility(View.VISIBLE);
                            handler.removeCallbacks(hideOverlay);
                            buffering.setVisibility(View.GONE);
                            bufferingLabel.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    /** Hand this queue channel to the embed player, queue intact. */
    private void openWebPlayer(final Channel c, final String videoId) {
        AppData.ytQueue = list;
        AppData.ytIndex = index;
        AppData.youtubeMode = true;
        AppData.playList = list;
        AppData.playIndex = index;
        AppData.playTitle = MainActivity.SECTION_NEWS;
        Intent i = new Intent(PlayerActivity.this, WebPlayerActivity.class);
        i.putExtra(WebPlayerActivity.EXTRA_VIDEO_ID, videoId);
        i.putExtra(WebPlayerActivity.EXTRA_TITLE, displayName(c.name) + " - YouTube Live");
        i.putExtra(WebPlayerActivity.EXTRA_ORIGINAL_URL, c.ytLiveUrl);
        stopped = true;
        startActivity(i);
        finish();
    }

    private void fitSurface(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float scale = Math.min((float) screenW / vw, (float) screenH / vh);
        int w = (int) (vw * scale);
        int h = (int) (vh * scale);
        android.view.ViewGroup.LayoutParams lp = surface.getLayoutParams();
        lp.width = w;
        lp.height = h;
        surface.setLayoutParams(lp);
    }

    private void togglePause() {
        if (mp == null) return;
        try {
            if (mp.isPlaying()) {
                mp.pause();
                paused = true;
                showOverlay(0);
            } else {
                mp.start();
                paused = false;
                showOverlay(2500);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                play(index + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                play(index - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                showOverlay(3500);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePause();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
