package nl.almutarjim.shamil;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_MIC = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private Uri camUri;
    private TextToSpeech tts;
    private boolean ttsReady;
    private SpeechRecognizer rec;
    private String pendingLang = "";
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                camUri = null;
                try {
                    Intent i;
                    if (params.isCaptureEnabled()) {
                        File f = new File(getCacheDir(), "shot_" + System.currentTimeMillis() + ".jpg");
                        camUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                        i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        ArrayList<String> mimes = new ArrayList<>();
                        String[] types = params.getAcceptTypes();
                        if (types != null) {
                            for (String t : types) {
                                if (t != null && t.contains("/")) mimes.add(t);
                            }
                        }
                        if (mimes.size() == 1) {
                            i.setType(mimes.get(0));
                        } else if (mimes.size() > 1) {
                            i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                        }
                    }
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    camUri = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });

        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCb == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (camUri != null) {
                result = new Uri[]{camUri};
            } else if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(result);
        fileCb = null;
        camUri = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListen(pendingLang);
        } else {
            js("window.__native.dict('denied','')");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (rec != null) rec.destroy();
        pool.shutdownNow();
        super.onDestroy();
    }

    private void js(final String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void startListen(String lang) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.__native.dict('error','unavailable')");
            return;
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    js("window.__native.dict('state','listening')");
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
                    js("window.__native.dict('state','idle')");
                }

                @Override
                public void onResults(Bundle results) {
                    String t = first(results);
                    if (!t.isEmpty()) js("window.__native.dict('final'," + JSONObject.quote(t) + ")");
                    js("window.__native.dict('state','idle')");
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    String t = first(partialResults);
                    if (!t.isEmpty()) js("window.__native.dict('partial'," + JSONObject.quote(t) + ")");
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (lang != null && !lang.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        rec.startListening(i);
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0) == null) return "";
        return list.get(0);
    }

    private void ok(String id, String text) {
        js("window.__native.done(" + JSONObject.quote(id) + ",200," + JSONObject.quote(text == null ? "" : text) + ")");
    }

    private void fail(String id, String code) {
        js("window.__native.done(" + JSONObject.quote(id) + ",0," + JSONObject.quote(code) + ")");
    }

    private void doTranslate(final String id, final String text, String from, String to) {
        String s = TranslateLanguage.fromLanguageTag(from);
        String t = TranslateLanguage.fromLanguageTag(to);
        if (s == null || t == null) {
            fail(id, "unsupported");
            return;
        }
        if (s.equals(t)) {
            ok(id, text);
            return;
        }
        final Translator tr = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(s).setTargetLanguage(t).build());
        tr.downloadModelIfNeeded()
                .addOnSuccessListener(v -> translateLines(tr, text.split("\n", -1), 0, new StringBuilder(), id))
                .addOnFailureListener(e -> {
                    tr.close();
                    fail(id, "offline");
                });
    }

    private void translateLines(final Translator tr, final String[] lines, final int start, final StringBuilder out, final String id) {
        int i = start;
        while (i < lines.length && lines[i].trim().isEmpty()) {
            if (i > 0) out.append('\n');
            i++;
        }
        if (i >= lines.length) {
            tr.close();
            ok(id, out.toString());
            return;
        }
        if (i > 0) out.append('\n');
        final int next = i + 1;
        tr.translate(lines[i])
                .addOnSuccessListener(r -> {
                    out.append(r);
                    translateLines(tr, lines, next, out, id);
                })
                .addOnFailureListener(e -> {
                    tr.close();
                    fail(id, "failed");
                });
    }

    private class Bridge {
        @JavascriptInterface
        public void translate(final String id, final String text, final String from, final String to) {
            runOnUiThread(() -> {
                if (from == null || from.isEmpty()) {
                    LanguageIdentification.getClient().identifyLanguage(text)
                            .addOnSuccessListener(code -> doTranslate(id, text, "und".equals(code) ? "en" : code, to))
                            .addOnFailureListener(e -> fail(id, "failed"));
                } else {
                    doTranslate(id, text, from, to);
                }
            });
        }

        @JavascriptInterface
        public void ocr(final String id, final String b64) {
            pool.execute(() -> {
                try {
                    byte[] d = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (bmp == null) {
                        fail(id, "bad_image");
                        return;
                    }
                    InputImage img = InputImage.fromBitmap(bmp, 0);
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                            .addOnSuccessListener(r -> ok(id, r.getText()))
                            .addOnFailureListener(e -> fail(id, "failed"));
                } catch (Exception e) {
                    fail(id, "failed");
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text, final String lang, final double rate) {
            runOnUiThread(() -> {
                if (!ttsReady || tts == null) return;
                if (lang != null && !lang.isEmpty()) tts.setLanguage(Locale.forLanguageTag(lang));
                tts.setSpeechRate((float) rate);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shamil");
            });
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
            });
        }

        @JavascriptInterface
        public void listen(final String lang) {
            runOnUiThread(() -> startListen(lang));
        }

        @JavascriptInterface
        public void stopListen() {
            runOnUiThread(() -> {
                if (rec != null) rec.stopListening();
            });
        }
    }
}
