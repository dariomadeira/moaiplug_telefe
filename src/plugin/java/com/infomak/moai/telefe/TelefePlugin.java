package com.infomak.moai.telefe;

import com.infomak.moai.contract.DrmInfo;
import com.infomak.moai.contract.IPlugin;
import com.infomak.moai.contract.PluginChannel;
import com.infomak.moai.contract.PluginManifest;
import com.infomak.moai.contract.ResolveRequest;
import com.infomak.moai.contract.ResolveResult;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin .dex v1 — Canal 11 (TELEFE, Argentina).
 *
 * Porte del MiTelefeScraper de moaiServer (src/services/scrapers.ts):
 *   1. GET https://www.mitelefe.com/vivo  ->  extrae LAMBDA_URL y STREAM_ID
 *   2. GET <LAMBDA_URL>?stream_id=<STREAM_ID>  ->  JSON con access_token
 *   3. URL final: https://mdstrm.com/live-stream-playlist/<STREAM_ID>.m3u8
 *      ?player=<PLAYER_ID>&access_token=...  + headers Referer/Origin
 *      https://www.mitelefe.com (UA móvil).
 *
 * El `player` es obligatorio: sin él mdstrm responde 302/Invalid Token. El
 * 302 devuelve el master real en cdn.mdstrm.com (HLS multi-variante).
 *
 * El plugin SOLO resuelve la señal; el motor la reproduce tal cual.
 * Instancia única por plugin (la cachea el host), así que el cache de
 * resolución vive en campos de instancia.
 */
public final class TelefePlugin implements IPlugin {

    private static final String DEFAULT_LAMBDA =
        "https://57j4mtvcjjf7siqnnvkixb5d3i0uvjei.lambda-url.us-east-1.on.aws/";
    private static final String DEFAULT_STREAM_ID = "6a024684fd4ca6a938f3a118";
    private static final String VIVO_PAGE = "https://www.mitelefe.com/telefe-en-vivo";
    private static final String MDSTRM_BASE =
        "https://mdstrm.com/live-stream-playlist/";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String REFERER = "https://www.mitelefe.com/";
    private static final String ORIGIN = "https://www.mitelefe.com";
    private static final String FORMAT_HLS = "hls";
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L; // 5 min (token mdstrm)
    private static final int HTTP_TIMEOUT_MS = 4000;

    private static final Pattern LAMBDA_RE =
        Pattern.compile("LAMBDA_URL\\s*=\\s*['\"]((https?://)[^'\"]+)['\"]");
    private static final Pattern STREAM_ID_RE =
        Pattern.compile("STREAM_ID\\s*=\\s*['\"]([a-f0-9]+)['\"]");
    private static final Pattern ACCESS_TOKEN_RE =
        Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STREAM_ID_RES_RE =
        Pattern.compile("\"stream_id\"\\s*:\\s*\"([a-f0-9]+)\"");
    private static final Pattern PLAYER_ID_RE =
        Pattern.compile("\"player_id\"\\s*:\\s*\"([a-f0-9]+)\"");

    private static final List<PluginChannel> CANALES;
    static {
        List<PluginChannel> list = new ArrayList<PluginChannel>();
        list.add(new PluginChannel(
            "canal_11",
            "TELEFE",
            "https://raw.githubusercontent.com/tv-logo/tv-logos/"
                + "refs/heads/main/countries/argentina/telefe-ar.png",
            "Aire",
            "Argentina"));
        CANALES = list;
    }

    // Cache de instancia (el host reutiliza la misma instancia entre resolve()).
    private String cachedUrl;
    private long cachedExpiresAt;

    public TelefePlugin() {
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(
            "moai_telefe",
            "Moai Telefe",
            "1.0.2",
            1,
            1,
            CANALES,
            "com.infomak.moai.telefe.TelefePlugin");
    }

    @Override
    public ResolveResult resolve(ResolveRequest request) {
        // canal_11 -> TELEFE en vivo, igual que moaiServer (siempre /vivo).
        if (request.getFallbackIndex() == 0 && cachedUrl != null
            && System.currentTimeMillis() < cachedExpiresAt) {
            return resultOf(cachedUrl);
        }

        String lambdaUrl = DEFAULT_LAMBDA;
        String streamId = DEFAULT_STREAM_ID;

        String vivoHtml;
        try {
            vivoHtml = httpGet(VIVO_PAGE);
        } catch (Exception e) {
            vivoHtml = null;
        }
        if (vivoHtml != null) {
            Matcher lm = LAMBDA_RE.matcher(vivoHtml);
            if (lm.find()) {
                lambdaUrl = lm.group(1);
            }
            Matcher sm = STREAM_ID_RE.matcher(vivoHtml);
            if (sm.find()) {
                streamId = sm.group(1);
            }
        }

        String json = httpGet(lambdaUrl + "?stream_id=" + streamId);
        Matcher am = ACCESS_TOKEN_RE.matcher(json);
        if (!am.find()) {
            throw new IllegalStateException(
                "MiTelefe: no se obtuvo access_token de " + lambdaUrl);
        }
        String accessToken = am.group(1);
        Matcher rm = STREAM_ID_RES_RE.matcher(json);
        if (rm.find()) {
            streamId = rm.group(1);
        }
        // El "player" es obligatorio: sin él mdstrm rechaza el token (302/Invalid).
        String playerId = "";
        Matcher pm = PLAYER_ID_RE.matcher(json);
        if (pm.find()) {
            playerId = pm.group(1);
        }

        String url = MDSTRM_BASE + streamId + ".m3u8?player=" + playerId
            + "&access_token=" + accessToken;
        cachedUrl = url;
        cachedExpiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
        return resultOf(url);
    }

    private static ResolveResult resultOf(String url) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Referer", REFERER);
        headers.put("Origin", ORIGIN);
        return new ResolveResult(url, headers, null, FORMAT_HLS, CACHE_TTL_MS);
    }

    private static String httpGet(String target) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(target);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            conn.connect();
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code <= 299
                ? conn.getInputStream()
                : conn.getErrorStream();
            if (code < 200 || code > 299) {
                throw new IllegalStateException("HTTP " + code + " en " + target);
            }
            return readFully(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Error HTTP en " + target + ": "
                + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readFully(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Error leyendo respuesta: " + e.getMessage(),
                e);
        }
    }

    // --------------------------------------------------------- autotest (JVM)
    public static void main(String[] args) throws Exception {
        TelefePlugin p = new TelefePlugin();
        System.out.println("manifest: " + p.manifest());
        ResolveResult r = p.resolve(new ResolveRequest("canal_11", 0));
        System.out.println("resolve: " + r);
    }
}