package com.hari.indiatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-app YouTube player, v2.4 (v2.2 cover/attempt-order fixes, v2.3
 * lip-sync watchdog, v2.4 quality: 1080p default + UP-arrow toggle).
 *
 * What Hari saw in v2.0/v2.1: the raw /embed page answers Error 152
 * ("This video is unavailable") and only a later attempt actually plays -
 * and every failed attempt was VISIBLE on screen first.
 *
 * Fixes:
 *  1. An opaque loading cover (channel name + spinner) sits on top of the
 *     WebView. Failed attempts happen invisibly behind it; the cover lifts
 *     only when a probe / the IFrame bridge confirms the video is really
 *     playing. YouTube error pages are never shown to the user.
 *  2. Attempt order flipped: the IFrame-API wrapper goes FIRST (it is the
 *     attempt that played in practice), then youtube.com/embed with the
 *     Referer/origin treatment from v1.4, then youtube-nocookie.
 *  3. Probes run faster (from 1.5s) so a dead attempt is abandoned in a
 *     few seconds, not twelve.
 * If all attempts fail, the dialog offers the channel's IPTV stream or the
 * YouTube app (unchanged from v1.4).
 *
 * v2.5.2: age-gate auto-switch (Hari's ask, 2026-10-24). YouTube put an
 * age restriction on Asianet News's live stream; a signed-out embed can
 * NEVER play an age-restricted video, so retrying the other attempts is
 * pointless. The probe now recognises the age-restriction page text on
 * the raw embed attempts and, instead of ending on the failure dialog,
 * the player announces it on the loading cover and switches to the
 * channel's IPTV stream by itself after a short pause.
 *
 * v2.6: channel zapping (Hari's ask, 2026-10-27). This player now receives
 * the whole YT News queue (AppData.ytQueue): UP / DOWN switch to the
 * previous / next news channel (its live is resolved fresh, native HLS
 * first if that works), and the quality toggle moved to LEFT.
 *
 * v2.6.1 hotfix: on the TV the zapping keys never arrived - the WebView
 * held focus and consumed every D-pad press for its own in-page focus
 * movement (all Hari saw was a blue focus border). Keys are now claimed
 * in dispatchKeyEvent before the WebView sees them, and the WebView is
 * made non-focusable so it cannot steal them back.
 */
public class WebPlayerActivity extends Activity {

    public static final String EXTRA_VIDEO_ID = "videoId";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ORIGINAL_URL = "originalUrl";

    private static final String PROBE_JS =
            "(function(){try{"
            + "var t=document.body?document.body.innerText:'';"
            + "if(/age[-\\s]restrict/i.test(t))return 'AGE';"
            + "var m=t.match(/Error (1[0-9][0-9])/);"
            + "if(m)return 'ERR:'+m[1];"
            + "if(document.querySelector('.ytp-error'))return 'ERR:ytp';"
            + "var v=document.querySelector('video');"
            + "if(v&&v.currentTime>0&&!v.paused)return 'PLAYING';"
            + "if(v)return 'LOADED:'+v.readyState;"
            + "return 'NONE';}catch(x){return 'NONE';}})()";

    private WebView web;
    private View cover;
    private TextView statusView;
    private TextView titleView;
    private List<Channel> queue;
    private int queueIndex = 0;
    private boolean channelSwitching = false;
    private int switchGeneration = 0;
    private boolean ageGate = false;
    private String videoId;
    private String originalUrl;
    private int attempt = 0;
    private int probeGen = 0;
    private String quality = "hd1080";
    private boolean settled = false;
    private String lastError = "";
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_webplayer);

        videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        originalUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_URL);
        if (videoId == null) {
            finish();
            return;
        }
        quality = getSharedPreferences("webplayer", MODE_PRIVATE)
                .getString("quality", "hd1080");
        cover = findViewById(R.id.cover);
        statusView = (TextView) findViewById(R.id.webStatus);
        titleView = (TextView) findViewById(R.id.webTitle);
        titleView.setText(title == null ? "" : title);

        // v2.6: adopt the shared YouTube queue, if one was handed over.
        if (AppData.ytQueue != null && !AppData.ytQueue.isEmpty()) {
            queue = AppData.ytQueue;
            queueIndex = AppData.ytIndex;
            if (queueIndex < 0 || queueIndex >= queue.size()) queueIndex = 0;
            Channel cur = currentChannel();
            if (cur != null) {
                if (originalUrl == null) originalUrl = cur.ytLiveUrl;
                titleView.setText(baseTitle(cur));
            }
            syncAppData();
        }

        web = (WebView) findViewById(R.id.webview);
        // v2.6.1: never let the WebView hold focus (see class header).
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        startAttempt(0);
    }

    private void startAttempt(int n) {
        if (n > 2) {
            showFailureDialog();
            return;
        }
        attempt = n;
        probeGen++;
        settled = false;
        if (n == 0) {
            // IFrame-API wrapper first - the attempt that actually plays.
            web.loadDataWithBaseURL("https://www.youtube.com/", wrapperHtml(videoId), "text/html", "UTF-8", null);
            scheduleProbes();
        } else if (n == 1) {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Referer", "https://www.youtube.com/");
            web.loadUrl("https://www.youtube.com/embed/" + videoId
                    + "?autoplay=1&rel=0&playsinline=1&fs=1&controls=0&modestbranding=1&disablekb=1&iv_load_policy=3&vq=" + quality + "&origin=https://www.youtube.com", h);
            scheduleProbes();
        } else {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Referer", "https://www.youtube-nocookie.com/");
            web.loadUrl("https://www.youtube-nocookie.com/embed/" + videoId
                    + "?autoplay=1&rel=0&playsinline=1&fs=1&controls=0&modestbranding=1&disablekb=1&iv_load_policy=3&vq=" + quality + "&origin=https://www.youtube-nocookie.com", h);
            scheduleProbes();
        }
    }

    // ---------- v2.6: channel queue / zapping ----------

    private Channel currentChannel() {
        if (queue == null || queueIndex < 0 || queueIndex >= queue.size()) return null;
        return queue.get(queueIndex);
    }

    private static String baseTitle(Channel c) {
        return MainActivity.baseName(c.name) + " - YouTube Live";
    }

    /** Keep the shared state pointing at the channel on screen. */
    private void syncAppData() {
        if (queue == null) return;
        AppData.ytQueue = queue;
        AppData.ytIndex = queueIndex;
        AppData.youtubeMode = true;
        AppData.playList = queue;
        AppData.playIndex = queueIndex;
        AppData.playTitle = MainActivity.SECTION_NEWS;
    }

    private boolean hasTvFallback() {
        Channel c = currentChannel();
        if (c != null) return c.url != null && c.url.length() > 0;
        if (AppData.playList == null || AppData.playIndex < 0
                || AppData.playIndex >= AppData.playList.size()) return false;
        String u = AppData.playList.get(AppData.playIndex).url;
        return u != null && u.length() > 0;
    }

    /** Leave YouTube and play the current channel's IPTV stream natively. */
    private void openTvFallback() {
        if (queue != null) {
            AppData.playList = queue;
            AppData.playIndex = queueIndex;
        }
        AppData.youtubeMode = false;
        AppData.playTitle = MainActivity.SECTION_NEWS;
        startActivity(new Intent(WebPlayerActivity.this, PlayerActivity.class));
        finish();
    }

    /** Reset this player onto a new channel whose live id is already known. */
    private void loadChannel(int idx, String newVideoId, String liveUrl) {
        queueIndex = idx;
        videoId = newVideoId;
        originalUrl = liveUrl;
        ageGate = false;
        lastError = "";
        settled = false;
        channelSwitching = false;
        syncAppData();
        Channel c = currentChannel();
        if (c != null) titleView.setText(baseTitle(c));
        if (statusView != null) statusView.setText("Loading live...");
        showCover();
        if (web != null) web.stopLoading();
        startAttempt(0);
    }

    /**
     * UP / DOWN zapping: move to the next / previous queue channel that has
     * a YouTube link, resolving its live fresh (native HLS first, then this
     * embed, then its TV stream). The current video keeps playing behind
     * the cover until the target is known to work; if it is not live, we
     * stay on the current channel.
     */
    private void switchChannel(int delta) {
        if (channelSwitching) return;
        if (queue == null || queue.size() <= 1) {
            Toast.makeText(this, "No other YouTube channel to switch to", Toast.LENGTH_SHORT).show();
            return;
        }
        int target = -1;
        for (int step = 1; step < queue.size(); step++) {
            int cand = ((queueIndex + delta * step) % queue.size() + queue.size()) % queue.size();
            Channel ch = queue.get(cand);
            if (ch.ytLiveUrl != null && ch.ytLiveUrl.length() > 0) {
                target = cand;
                break;
            }
        }
        if (target < 0) {
            Toast.makeText(this, "No other channel has a YouTube live link", Toast.LENGTH_SHORT).show();
            return;
        }
        final int targetIndex = target;
        final Channel targetChannel = queue.get(target);
        final int gen = ++switchGeneration;
        channelSwitching = true;
        // Pending probes belong to the old channel now - cancel them.
        handler.removeCallbacksAndMessages(null);
        showCover();
        titleView.setText(baseTitle(targetChannel));
        if (statusView != null) {
            statusView.setText("Finding the latest live for "
                    + MainActivity.baseName(targetChannel.name) + "...");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String hls = YtResolver.resolveHls(targetChannel.ytLiveUrl);
                if (hls != null && !YtResolver.isPlayableHls(hls)) {
                    hls = YtResolver.resolveHls(targetChannel.ytLiveUrl);
                    if (hls != null && !YtResolver.isPlayableHls(hls)) hls = null;
                }
                final String stream = hls;
                final String vid = stream == null
                        ? YtResolver.liveVideoId(targetChannel.ytLiveUrl) : null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != switchGeneration || isFinishing()) return;
                        if (stream != null) {
                            targetChannel.ytResolvedUrl = stream;
                            queueIndex = targetIndex;
                            syncAppData();
                            channelSwitching = false;
                            startActivity(new Intent(WebPlayerActivity.this, PlayerActivity.class));
                            finish();
                        } else if (vid != null) {
                            loadChannel(targetIndex, vid, targetChannel.ytLiveUrl);
                        } else if (targetChannel.url != null && targetChannel.url.length() > 0) {
                            queueIndex = targetIndex;
                            channelSwitching = false;
                            Toast.makeText(WebPlayerActivity.this,
                                    "YouTube live is offline - playing the TV stream",
                                    Toast.LENGTH_LONG).show();
                            openTvFallback();
                        } else {
                            channelSwitching = false;
                            Toast.makeText(WebPlayerActivity.this,
                                    MainActivity.baseName(targetChannel.name)
                                            + " is not live on YouTube right now",
                                    Toast.LENGTH_LONG).show();
                            Channel cur = currentChannel();
                            if (cur != null) titleView.setText(baseTitle(cur));
                            if (settled) {
                                hideCover();
                            } else {
                                if (statusView != null) statusView.setText("Loading live...");
                                startAttempt(attempt);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private void scheduleProbes() {
        final int thisAttempt = attempt;
        final int gen = probeGen;
        int[] delays = {1500, 3000, 4500, 6000, 8000, 10000, 13000};
        for (int i = 0; i < delays.length; i++) {
            final boolean last = i == delays.length - 1;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (settled || attempt != thisAttempt || gen != probeGen || web == null) return;
                    web.evaluateJavascript(PROBE_JS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (settled || attempt != thisAttempt || gen != probeGen) return;
                            if (value == null) value = "";
                            if (value.contains("PLAYING")) {
                                onPlaying();
                            } else if (value.contains("AGE")) {
                                // Age-restricted stream: no signed-out
                                // attempt can ever play this - switch to
                                // the channel's TV stream instead.
                                onAgeGate();
                            } else if (value.contains("ERR:")) {
                                // Behind the cover, nobody sees this error -
                                // just move on to the next attempt.
                                if (thisAttempt != 0) {
                                    lastError = value.replace("\"", "").replace("ERR:", "");
                                }
                                startAttempt(thisAttempt + 1);
                            } else if (last) {
                                lastError = "timeout";
                                startAttempt(thisAttempt + 1);
                            }
                        }
                    });
                }
            }, delays[i]);
        }
    }

    private void onPlaying() {
        settled = true;
        hideCover();
    }

    private void hideCover() {
        if (cover.getVisibility() == View.GONE) return;
        // Lift the cover - from here the user sees only the video.
        cover.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
            @Override
            public void run() {
                cover.setVisibility(View.GONE);
            }
        }).start();
    }

    private void showCover() {
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
    }

    /**
     * Re-sync: reload the live at its live edge with a fresh decoder start.
     * Clears accumulated audio/video drift. Bound to Right arrow / FF key,
     * and fired automatically by the wrapper's stall watchdog.
     */
    private void resync() {
        if (web == null || channelSwitching) return;
        showCover();
        if (attempt == 0) {
            web.evaluateJavascript("resyncLive()", null);
        } else {
            int a = attempt;
            settled = false;
            startAttempt(a);
        }
    }

    /**
     * Quality toggle (v2.4), moved to the LEFT arrow in v2.6 (UP / DOWN are
     * channel zapping now): switches 1080p <-> 720p, remembers the choice,
     * and restarts playback at the new quality behind the loading cover.
     */
    private void toggleQuality() {
        if (channelSwitching) return;
        quality = "hd1080".equals(quality) ? "hd720" : "hd1080";
        getSharedPreferences("webplayer", MODE_PRIVATE).edit()
                .putString("quality", quality).apply();
        Toast.makeText(this, "Quality: " + ("hd1080".equals(quality) ? "1080p" : "720p"),
                Toast.LENGTH_SHORT).show();
        if (statusView != null) statusView.setText("Loading live...");
        showCover();
        startAttempt(0);
    }

    /**
     * v2.5.2: the stream is age-restricted on YouTube. No embed attempt
     * can fix that (the player is signed out), so don't strand the user
     * on a dialog: say what's happening on the loading cover and switch
     * to the channel's IPTV stream automatically after a short pause.
     * Falls back to the dialog when the channel has no TV stream.
     */
    private void onAgeGate() {
        if (settled) return;
        settled = true;
        ageGate = true;
        probeGen++; // pending probes are now stale - ignore them
        if (hasTvFallback()) {
            if (statusView != null) {
                statusView.setText("Age-restricted on YouTube - switching to TV stream...");
            }
            final int gen = probeGen;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || gen != probeGen || !ageGate) return;
                    openTvFallback();
                }
            }, 2200);
        } else {
            showFailureDialog();
        }
    }

    private void showFailureDialog() {
        settled = true;
        cover.setVisibility(View.GONE);
        String msg;
        if (ageGate) {
            msg = "YouTube has age-restricted this stream, so it can't play inside the app.";
        } else {
            msg = "YouTube won't play this channel inside the app"
                    + (lastError.length() > 0 ? " (error " + lastError + ")" : "") + ".";
        }
        boolean tv = hasTvFallback();
        if (tv) msg += "\n\nThe channel's TV stream still works:";
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("YouTube blocked in-app play")
                .setMessage(msg);
        if (tv) {
            b.setPositiveButton("Play TV stream", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openTvFallback();
                }
            });
        }
        b.setNeutralButton("Open YouTube app", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openOriginal();
                        finish();
                    }
                })
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                });
        b.show();
    }

    private String wrapperHtml(String vid) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<style>html,body{margin:0;padding:0;background:#000;height:100%}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>"
                + "</head><body><div id='p'></div>"
                + "<script src='https://www.youtube.com/iframe_api'></script>"
                + "<script>var player,lastCt=0,lastWall=0;"
                // Quality, v2.4: the v2.3 720p lock looked too soft on
                // Hari's big TV, so the default is 1080p now. The stall
                // watchdog below still guards lip-sync at 1080p, and the
                // LEFT arrow toggles 1080p/720p (persisted) if drift returns.
                + "var Q='" + quality + "';"
                + "function lockQ(){try{player.setPlaybackQuality(Q);}catch(x){}}"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + vid + "',"
                + "playerVars:{autoplay:1,rel:0,playsinline:1,fs:1,controls:0,modestbranding:1,disablekb:1,iv_load_policy:3,origin:'https://www.youtube.com'},"
                + "events:{onReady:function(e){e.target.setPlaybackQuality(Q);e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data==1){lockQ();lastCt=player.getCurrentTime();lastWall=Date.now();AndroidBridge.playing();}},"
                + "onPlaybackQualityChange:function(e){if(e.data!=Q)lockQ();},"
                + "onError:function(e){AndroidBridge.error(e.data);}}});}"
                + "function togglePlay(){if(!player||!player.getPlayerState)return;var st=player.getPlayerState();if(st==1){player.pauseVideo();}else{player.playVideo();}}"
                + "function resyncLive(){if(player&&player.loadVideoById){lastWall=0;player.loadVideoById({videoId:'" + vid + "'});lockQ();}}"
                // Stall watchdog: every 15s compare media time with wall
                // time. If the picture clock advanced much less than real
                // time while 'playing', decoding is stalling - reload at
                // the live edge to re-lock audio and video.
                + "setInterval(function(){try{if(!player||player.getPlayerState()!=1)return;"
                + "var ct=player.getCurrentTime(),now=Date.now();"
                + "if(lastWall>0){var wa=(now-lastWall)/1000,ma=ct-lastCt;"
                + "if(wa>=14&&ma<wa-4){AndroidBridge.stalled();}}"
                + "lastCt=ct;lastWall=now;}catch(x){}},15000);"
                + "</script></body></html>";
    }

    public class Bridge {
        @JavascriptInterface
        public void playing() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (channelSwitching) return; // the old channel, mid-zap
                    if (attempt == 0) onPlaying();
                }
            });
        }

        @JavascriptInterface
        public void stalled() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    resync();
                }
            });
        }

        @JavascriptInterface
        public void error(final int code) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (settled || channelSwitching) return;
                    lastError = String.valueOf(code);
                    startAttempt(attempt + 1);
                }
            });
        }
    }

    private void openOriginal() {
        if (originalUrl == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl));
            i.setPackage("com.google.android.youtube.tv");
            startActivity(i);
        } catch (ActivityNotFoundException e1) {
            try {
                Intent i2 = new Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl));
                i2.setPackage("org.smarttube.stable");
                startActivity(i2);
            } catch (ActivityNotFoundException e2) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl)));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
    }

    /**
     * v2.6.1: remote keys are claimed here, in dispatchKeyEvent, BEFORE the
     * view hierarchy (the WebView) can consume them. In v2.6 they only
     * existed in onKeyDown, which a focused WebView never lets fire for
     * D-pad keys - it eats them for in-page focus movement instead.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isRemoteKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return handleKey(event.getKeyCode());
            }
            return true; // swallow the matching ACTION_UP as well
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isRemoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Backstop for when no view has focus; dispatchKeyEvent is the
        // primary path and normally handles these first.
        if (isRemoteKey(keyCode)) return handleKey(keyCode);
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            // v2.6: next YouTube news channel.
            switchChannel(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            // v2.6: previous YouTube news channel.
            switchChannel(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            // v2.6: quality toggle moved here (was UP before v2.6).
            toggleQuality();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            // Manual lip-sync fix: jump back to the live edge.
            resync();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (web != null && !channelSwitching) {
                if (attempt == 0) {
                    web.evaluateJavascript("togglePlay()", null);
                } else {
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE));
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE));
                }
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
