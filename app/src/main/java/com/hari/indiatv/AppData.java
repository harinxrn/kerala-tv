package com.hari.indiatv;

import java.util.List;

/**
 * Playback state shared between MainActivity and the two players.
 *
 * playList / playIndex: plain stream list for the native player (TV section,
 * or a YouTube channel's IPTV fallback).
 *
 * ytQueue / ytIndex (v2.6): the ordered YT News channels for YouTube
 * playback, so UP / DOWN can zap between news channels inside BOTH players.
 * Each queue entry keeps its permanent /live URL in ytLiveUrl and its IPTV
 * stream in url (the fallback). youtubeMode tells PlayerActivity which of
 * the two lists it was launched with.
 */
public class AppData {
    public static List<Channel> playList;
    public static int playIndex;
    public static String playTitle = "";

    public static List<Channel> ytQueue;
    public static int ytIndex;
    public static boolean youtubeMode;
}
