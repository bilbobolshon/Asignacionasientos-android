package es.fran.asignacionasientos;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Asignacion de asientos: la pantalla es una pagina interna (assets/www) y Android aporta
 * lo que el navegador no deja: voz continua, permisos, archivos y compartir.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_FILE = 10;
    private static final int REQ_MIC = 11;
    private static final int REQ_WEB_PERMS = 12;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler voiceTimer = new Handler(Looper.getMainLooper());

    // archivos y permisos de la pagina
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPerm;

    // voz
    private SpeechRecognizer recognizer;
    private boolean voiceWanted = false;
    private boolean voicePaused = false;
    private int errorStreak = 0;
    private AudioManager audio;
    private boolean muted = false;
    private ToneGenerator tone;

    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 80);
        } catch (Exception e) {
            tone = null;
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(242, 243, 239));
        setContentView(web);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                ui.post(() -> handleWebPermission(request));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean pdf = false;
                String[] acc = params.getAcceptTypes();
                if (acc != null) {
                    for (String a : acc) {
                        if (a != null && a.toLowerCase().contains("pdf")) pdf = true;
                    }
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(pdf ? "application/pdf" : "*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, pdf ? "Elige los PDF" : "Elige el archivo"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    // ------------------------------------------------------------------
    // Permisos que pide la pagina (camara)
    // ------------------------------------------------------------------
    private void handleWebPermission(PermissionRequest request) {
        ArrayList<String> needed = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA)) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO)) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        if (pendingWebPerm != null) pendingWebPerm.deny();
        pendingWebPerm = request;
        requestPermissions(needed.toArray(new String[0]), REQ_WEB_PERMS);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean all = results.length > 0;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) all = false;
        }
        if (requestCode == REQ_WEB_PERMS && pendingWebPerm != null) {
            if (all) pendingWebPerm.grant(pendingWebPerm.getResources());
            else pendingWebPerm.deny();
            pendingWebPerm = null;
        } else if (requestCode == REQ_MIC) {
            if (all && voiceWanted) {
                beginVoice();
            } else {
                voiceWanted = false;
                sendVoiceError(true, "Sin permiso para el micrófono",
                        "Acepta el permiso. Si lo denegaste: Ajustes > Aplicaciones > Asignación asientos > Permisos > Micrófono.");
            }
        }
    }

    // ------------------------------------------------------------------
    // Archivo elegido (listado PDF o CSV)
    // ------------------------------------------------------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                result = new Uri[cd.getItemCount()];
                for (int i = 0; i < cd.getItemCount(); i++) result[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    // ------------------------------------------------------------------
    // Voz continua con el reconocimiento de Android
    // ------------------------------------------------------------------
    private void beginVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceWanted = false;
            sendVoiceError(true, "Este móvil no tiene reconocimiento de voz activo",
                    "Instala o activa «Servicios de voz de Google» o el dictado de Samsung y vuelve a pulsar Voz.");
            return;
        }
        errorStreak = 0;
        muteBeeps(true);
        listen();
    }

    private void listen() {
        if (!voiceWanted || voicePaused) return;
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
            recognizer.startListening(i);
        } catch (Exception e) {
            restartVoice(600, true);
        }
    }

    private void restartVoice(long delay, boolean recreate) {
        if (!voiceWanted) return;
        if (recreate && recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        voiceTimer.postDelayed(this::listen, delay);
    }

    private void stopVoice() {
        voiceWanted = false;
        voiceTimer.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        muteBeeps(false);
    }

    /** El reconocimiento de Android pita cada vez que vuelve a escuchar: lo silenciamos mientras dura. */
    private void muteBeeps(boolean on) {
        if (audio == null || muted == on) return;
        try {
            int dir = on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
            muted = on;
        } catch (Exception ignored) {
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            JSONObject o = new JSONObject();
            put(o, "t", "ready");
            sendVoice(o);
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            if (!voiceWanted) return;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    errorStreak = 0;
                    restartVoice(80, false);
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopVoice();
                    sendVoiceError(true, "Sin permiso para el micrófono",
                            "Ajustes > Aplicaciones > Asignación asientos > Permisos > Micrófono.");
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    errorStreak++;
                    if (errorStreak == 3) {
                        sendVoiceError(false, "La voz no tiene conexión",
                                "Sigue intentándolo. Si no hay cobertura, descarga el español sin conexión en los ajustes de voz del móvil.");
                    }
                    restartVoice(1500, true);
                    return;
                default:
                    // ocupado, cliente, etc.: se rehace el reconocedor y se sigue escuchando
                    errorStreak++;
                    if (errorStreak > 25) {
                        stopVoice();
                        sendVoiceError(true, "El micrófono no responde", "Vuelve a pulsar Voz.");
                        return;
                    }
                    restartVoice(errorStreak > 5 ? 1200 : 300, true);
            }
        }

        @Override
        public void onResults(Bundle results) {
            errorStreak = 0;
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                JSONObject o = new JSONObject();
                put(o, "t", "result");
                JSONArray a = new JSONArray();
                for (String s : list) a.put(s);
                put(o, "alts", a);
                sendVoice(o);
            }
            restartVoice(60, false);
        }

        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                JSONObject o = new JSONObject();
                put(o, "t", "partial");
                put(o, "text", list.get(0));
                sendVoice(o);
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private void sendVoice(JSONObject o) {
        final String js = "window.nativeVoice && window.nativeVoice(" + o.toString() + ")";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    private void sendVoiceError(boolean fatal, String title, String msg) {
        JSONObject o = new JSONObject();
        put(o, "t", "error");
        put(o, "fatal", fatal);
        put(o, "title", title);
        put(o, "msg", msg);
        sendVoice(o);
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------
    @Override
    protected void onPause() {
        super.onPause();
        if (voiceWanted) {
            voicePaused = true;
            voiceTimer.removeCallbacksAndMessages(null);
            if (recognizer != null) {
                try {
                    recognizer.cancel();
                } catch (Exception ignored) {
                }
            }
            muteBeeps(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (voicePaused) {
            voicePaused = false;
            if (voiceWanted) {
                muteBeeps(true);
                restartVoice(300, true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopVoice();
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.handleBack && window.handleBack()) ? 'yes' : 'no'", value -> {
            if (value == null || !value.contains("yes")) {
                finish();
            }
        });
    }

    // ------------------------------------------------------------------
    // Archivos PDF generados
    // ------------------------------------------------------------------
    private File writeCachePdf(String b64, String name) throws Exception {
        File dir = new File(getCacheDir(), "pdf");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("sin carpeta");
        File f = new File(dir, safeName(name));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(Base64.decode(b64, Base64.DEFAULT));
        }
        return f;
    }

    private static String safeName(String n) {
        String s = (n == null || n.isEmpty()) ? "AsignacionAsientos.pdf" : n.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.toLowerCase().endsWith(".pdf") ? s : s + ".pdf";
    }

    private String saveToDownloads(String b64, String name) {
        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
        String file = safeName(name);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AsignacionAsientos");
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "";
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) return "";
                    out.write(bytes);
                }
                return "PDF guardado en Descargas/AsignacionAsientos";
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) return "";
                File f = new File(dir, file);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(bytes);
                }
                return "PDF guardado en " + f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Funciones que la pagina puede llamar: window.Android.xxx()
    // ------------------------------------------------------------------
    private class Bridge {
        @JavascriptInterface
        public void startVoice() {
            ui.post(() -> {
                voiceWanted = true;
                voicePaused = false;
                if (has(Manifest.permission.RECORD_AUDIO)) {
                    beginVoice();
                } else {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            });
        }

        @JavascriptInterface
        public void stopVoice() {
            ui.post(MainActivity.this::stopVoice);
        }

        @JavascriptInterface
        public void beep(boolean ok) {
            try {
                if (tone != null) {
                    tone.startTone(ok ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_SUP_ERROR, ok ? 150 : 350);
                }
            } catch (Exception ignored) {
            }
            try {
                Vibrator vb = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vb != null) {
                    long ms = ok ? 60 : 250;
                    if (Build.VERSION.SDK_INT >= 26) {
                        vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vb.vibrate(ms);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void copy(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AsignacionAsientos", text));
            });
        }

        @JavascriptInterface
        public void sharePdf(String b64, String name) {
            try {
                File f = writeCachePdf(b64, name);
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                ui.post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/pdf");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(send, "Enviar PDF"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "No hay ninguna app para compartir", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(MainActivity.this, "No se pudo preparar el PDF", Toast.LENGTH_LONG).show());
            }
        }

        /** Brillo al máximo mientras se enseña el QR del billete, para que lo lea el lector de la estación. */
        @JavascriptInterface
        public void bright(boolean on) {
            ui.post(() -> {
                try {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = on ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    getWindow().setAttributes(lp);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public String savePdf(String b64, String name) {
            return saveToDownloads(b64, name);
        }
    }
}
