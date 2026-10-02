package com.azrix.life;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole app is app/src/main/assets/index.html. This class displays it, lets it store data
 * locally, and gives it three things a web page cannot do on its own:
 *   - take a photo or pick a photo / PDF from the phone,
 *   - read the text in a photo (Google ML Kit, bundled model — runs offline, nothing is uploaded),
 *   - pick a profile photo.
 * The page calls window.AzrixNative.pick(id, kind) / ocr(id, dataUrl) and gets the answer back
 * through window.AzrixNativeResult(id, json).
 */
public class MainActivity extends AppCompatActivity {

    private static final int MAX_PDF_BYTES = 25 * 1024 * 1024;
    private static final int MAX_OCR_PIXELS = 16_000_000;
    private static final int MAX_OCR_SIDE = 7000;

    private WebView web;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;

    // one picker at a time
    private String pendingId;
    private String pendingKind;
    private Uri cameraUri;

    private ActivityResultLauncher<String> pickImage;
    private ActivityResultLauncher<String[]> pickDocument;
    private ActivityResultLauncher<Uri> takePicture;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // pickers must be registered before the activity starts
        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPicked);
        pickDocument = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            Uri u = cameraUri;
            cameraUri = null;
            onPicked(Boolean.TRUE.equals(ok) ? u : null);
        });

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage — this is where every entry lives
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);           // the page is served from assets; it needs nothing else
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);                    // the layout sizes itself; ignore system font scaling

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true);
        }

        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);

        // Nothing in this app should ever leave the page — there is no network permission anyway.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;
            }
        });

        WebView.setWebContentsDebuggingEnabled(false);

        web.addJavascriptInterface(new Bridge(), "AzrixNative");
        web.loadUrl("file:///android_asset/index.html");
    }

    /* ------------------------------------------------------------------ */
    /* the bridge the page talks to                                        */
    /* ------------------------------------------------------------------ */

    private final class Bridge {

        /** kind: ocr-camera | ocr-photo | pdf | avatar | avatar-camera */
        @JavascriptInterface
        public void pick(String id, String kind) {
            runOnUiThread(() -> startPick(id, kind));
        }

        /** read the text in an image the page already has (a page of a scanned PDF) */
        @JavascriptInterface
        public void ocr(String id, String dataUrl) {
            work.execute(() -> {
                try {
                    String b64 = dataUrl == null ? "" : dataUrl;
                    int comma = b64.indexOf(',');
                    if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp == null) throw new Exception("Could not open the image");
                    JSONObject r = readText(bmp, "", false);
                    bmp.recycle();
                    send(id, r);
                } catch (Throwable e) {
                    send(id, fail(e));
                }
            });
        }

        @JavascriptInterface
        public void info(String id) {
            try {
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("ocr", true);
                r.put("version", BuildConfig.VERSION_NAME);
                r.put("android", Build.VERSION.SDK_INT);
                send(id, r);
            } catch (Throwable e) {
                send(id, fail(e));
            }
        }
    }

    private void startPick(String id, String kind) {
        if (pendingId != null) {
            // a picker is already open (or was abandoned) — cancel the older request
            send(pendingId, cancelled());
        }
        pendingId = id;
        pendingKind = kind == null ? "" : kind;
        try {
            switch (pendingKind) {
                case "ocr-camera":
                case "avatar-camera": {
                    File dir = new File(getCacheDir(), "captures");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, "capture.jpg");
                    if (f.exists()) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                    takePicture.launch(cameraUri);
                    break;
                }
                case "pdf":
                    pickDocument.launch(new String[]{"application/pdf"});
                    break;
                case "ocr-photo":
                case "avatar":
                    pickImage.launch("image/*");
                    break;
                default:
                    finishPick(fail(new Exception("Unknown request")));
            }
        } catch (Throwable e) {
            finishPick(fail(new Exception("No camera or file app is available")));
        }
    }

    private void onPicked(Uri uri) {
        final String id = pendingId, kind = pendingKind;
        pendingId = null;
        pendingKind = null;
        if (id == null) return;
        if (uri == null) {
            send(id, cancelled());
            return;
        }
        work.execute(() -> {
            try {
                send(id, handle(uri, kind));
            } catch (Throwable e) {
                send(id, fail(e));
            }
        });
    }

    private void finishPick(JSONObject r) {
        String id = pendingId;
        pendingId = null;
        pendingKind = null;
        if (id != null) send(id, r);
    }

    private JSONObject handle(Uri uri, String kind) throws Exception {
        String name = displayName(uri);
        switch (kind) {
            case "pdf": {
                byte[] bytes = readAll(uri, MAX_PDF_BYTES);
                if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F')
                    throw new Exception("That file is not a PDF");
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("kind", "pdf");
                r.put("name", name);
                r.put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                return r;
            }
            case "avatar":
            case "avatar-camera": {
                Bitmap bmp = decode(uri, 1600 * 1600, 1600);
                Bitmap sq = squareCrop(bmp, 360);
                if (sq != bmp) bmp.recycle();
                String url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg(sq, 85), Base64.NO_WRAP);
                sq.recycle();
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("kind", "avatar");
                r.put("dataUrl", url);
                return r;
            }
            default: { // ocr-camera, ocr-photo
                Bitmap bmp = decode(uri, MAX_OCR_PIXELS, MAX_OCR_SIDE);
                JSONObject r = readText(bmp, name, true);
                bmp.recycle();
                return r;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* text recognition                                                    */
    /* ------------------------------------------------------------------ */

    private synchronized TextRecognizer recognizer() {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return recognizer;
    }

    /** {ok, kind:"ocr", name, w, h, preview, lines:[{t, a, b:[l,t,r,b], w:[{t, b}]}]} */
    private JSONObject readText(Bitmap bmp, String name, boolean withPreview) throws Exception {
        Text text = Tasks.await(recognizer().process(InputImage.fromBitmap(bmp, 0)));
        JSONArray lines = new JSONArray();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect lb = line.getBoundingBox();
                if (lb == null) continue;
                JSONArray words = new JSONArray();
                for (Text.Element el : line.getElements()) {
                    Rect eb = el.getBoundingBox();
                    if (eb == null) continue;
                    JSONObject w = new JSONObject();
                    w.put("t", el.getText());
                    w.put("b", box(eb));
                    words.put(w);
                }
                if (words.length() == 0) continue;
                JSONObject l = new JSONObject();
                l.put("t", line.getText());
                l.put("a", (double) line.getAngle());
                l.put("b", box(lb));
                l.put("w", words);
                lines.put(l);
            }
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("kind", "ocr");
        r.put("name", name == null ? "" : name);
        r.put("w", bmp.getWidth());
        r.put("h", bmp.getHeight());
        r.put("lines", lines);
        if (withPreview) {
            Bitmap p = scaleTo(bmp, 520);
            r.put("preview", "data:image/jpeg;base64," + Base64.encodeToString(jpeg(p, 70), Base64.NO_WRAP));
            if (p != bmp) p.recycle();
        }
        return r;
    }

    private static JSONArray box(Rect b) {
        JSONArray a = new JSONArray();
        a.put(b.left);
        a.put(b.top);
        a.put(b.right);
        a.put(b.bottom);
        return a;
    }

    /* ------------------------------------------------------------------ */
    /* images                                                              */
    /* ------------------------------------------------------------------ */

    /** decode with downsampling and the EXIF rotation applied */
    private Bitmap decode(Uri uri, int maxPixels, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("Could not open that picture");
        int sample = 1;
        while ((long) (o.outWidth / sample) * (o.outHeight / sample) > (long) maxPixels * 2
                || Math.max(o.outWidth / sample, o.outHeight / sample) > maxSide * 2) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, d);
        }
        if (bmp == null) throw new Exception("Could not open that picture");

        // exact fit inside the limits
        double k = 1.0;
        long px = (long) bmp.getWidth() * bmp.getHeight();
        if (px > maxPixels) k = Math.min(k, Math.sqrt((double) maxPixels / px));
        int side = Math.max(bmp.getWidth(), bmp.getHeight());
        if (side > maxSide) k = Math.min(k, (double) maxSide / side);

        int rot = 0;
        boolean flip = false;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                ExifInterface ex = new ExifInterface(in);
                int or = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (or) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flip = true; break;
                    case ExifInterface.ORIENTATION_TRANSPOSE: rot = 90; flip = true; break;
                    case ExifInterface.ORIENTATION_TRANSVERSE: rot = 270; flip = true; break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL: rot = 180; flip = true; break;
                    default: break;
                }
            }
        } catch (Throwable ignored) {
            // not every image carries EXIF
        }
        if (k >= 0.999 && rot == 0 && !flip) return bmp;
        Matrix m = new Matrix();
        if (k < 0.999) m.postScale((float) k, (float) k);
        if (flip) m.postScale(-1, 1);
        if (rot != 0) m.postRotate(rot);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    private static Bitmap squareCrop(Bitmap src, int size) {
        int s = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - s) / 2, y = (src.getHeight() - s) / 2;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, new Rect(x, y, x + s, y + s), new Rect(0, 0, size, size), null);
        return out;
    }

    private static Bitmap scaleTo(Bitmap src, int maxSide) {
        int side = Math.max(src.getWidth(), src.getHeight());
        if (side <= maxSide) return src;
        double k = (double) maxSide / side;
        return Bitmap.createScaledBitmap(src, Math.max(1, (int) (src.getWidth() * k)), Math.max(1, (int) (src.getHeight() * k)), true);
    }

    private static byte[] jpeg(Bitmap b, int q) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, bo);
        return bo.toByteArray();
    }

    /* ------------------------------------------------------------------ */
    /* files and replies                                                   */
    /* ------------------------------------------------------------------ */

    private byte[] readAll(Uri uri, int max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Could not open that file");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new Exception("That file is too large (over " + (max / 1024 / 1024) + " MB)");
            }
            return bo.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        String p = uri.getLastPathSegment();
        return p == null ? "" : p;
    }

    private static JSONObject fail(Throwable e) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false);
            String m = e instanceof OutOfMemoryError ? "That picture is too large to read" : e.getMessage();
            Throwable c = e.getCause();
            if ((m == null || m.isEmpty()) && c != null) m = c.getMessage();
            r.put("error", m == null || m.isEmpty() ? "Something went wrong" : m);
        } catch (Throwable ignored) {
            // cannot fail
        }
        return r;
    }

    private static JSONObject cancelled() {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false);
            r.put("error", "cancelled");
        } catch (Throwable ignored) {
            // cannot fail
        }
        return r;
    }

    private void send(String id, JSONObject r) {
        final String js = "window.AzrixNativeResult&&window.AzrixNativeResult(" + JSONObject.quote(id) + "," + r.toString() + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /** Back closes an open sheet, then returns to Today, then leaves the app. */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraUri != null) out.putParcelable("cameraUri", cameraUri);
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        Uri u = in.getParcelable("cameraUri");
        if (u != null) cameraUri = u;
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        if (recognizer != null) recognizer.close();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
