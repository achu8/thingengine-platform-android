package edu.stanford.thingengine.engine.ui;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.List;

import edu.stanford.thingengine.engine.CloudAuthInfo;
import edu.stanford.thingengine.engine.R;
import edu.stanford.thingengine.engine.service.ControlBinder;


public class ThingpediaWebsiteActivity extends Activity {
    private final EngineServiceConnection mEngine = new EngineServiceConnection();
    private volatile CloudAuthInfo mAuthInfo;

    private class WebChromeClient extends android.webkit.WebChromeClient {
        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result)
        {
            new AlertDialog.Builder(ThingpediaWebsiteActivity.this)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                            new AlertDialog.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    result.confirm();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel,
                            new AlertDialog.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    result.cancel();
                                }
                            })
                    .setCancelable(false)
                    .create()
                    .show();

            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result)
        {
            new AlertDialog.Builder(ThingpediaWebsiteActivity.this)
                    .setTitle("Alert")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                            new AlertDialog.OnClickListener()
                            {
                                public void onClick(DialogInterface dialog, int which)
                                {
                                    result.confirm();
                                }
                            })
                    .setCancelable(false)
                    .create()
                    .show();

            return true;
        }
    }

    private class WebViewClient extends android.webkit.WebViewClient {
        @Override
        public void onReceivedHttpAuthRequest (WebView view, @NonNull HttpAuthHandler handler, String host, String realm) {
            CloudAuthInfo authInfo = mAuthInfo;
            if (authInfo == null || !authInfo.isValid()) {
                handler.cancel();
                return;
            }

            if (!"thingengine.stanford.edu".equals(host)) {
                handler.cancel();
                return;
            }

            handler.proceed(authInfo.getCloudId(), authInfo.getAuthToken());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri parsed = Uri.parse(url);
            if (parsed.getAuthority().equals("thingengine.stanford.edu"))
                return false;

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
            return true;
        }
    }

    private String readStringPref(SharedPreferences prefs, String key) {
        try {
            // shared preferences have one extra layer of json that we need to unwrap
            Object obj = new JSONTokener(prefs.getString(key, "null")).nextValue();
            return obj == JSONObject.NULL ? null : (String) obj;
        } catch(JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private void initAuthInfo() {
        SharedPreferences prefs = getSharedPreferences("thingengine", Context.MODE_PRIVATE);

        String cloudId = readStringPref(prefs, "cloud-id");
        String authToken = readStringPref(prefs, "auth-token");

        mAuthInfo = new CloudAuthInfo(cloudId, authToken);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thingpedia_web_ui);

        ActionBar actionBar = getActionBar();
        if (actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);

        initAuthInfo();

        WebView view = (WebView)findViewById(R.id.thingpedia_web_view);
        view.addJavascriptInterface(this, "Android");
        view.getSettings().setJavaScriptEnabled(true);
        view.setWebChromeClient(new WebChromeClient());
        view.setWebViewClient(new WebViewClient());
        view.loadUrl("https://thingengine.stanford.edu/user/login?auth=app");
    }

    private void showConfirmDialog(boolean success) {
        new AlertDialog.Builder(this)
                .setMessage(success ? "Congratulations, you're now all set to use ThingEngine!"
                            : "Sorry, that did not work")
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_info)
                .show();
    }

    private class SetCloudIdTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected Boolean doInBackground(String... params) {
            String cloudId = params[0];
            String authToken = params[1];

            ControlBinder control = mEngine.getControl();
            if (control == null)
                return true;
            CloudAuthInfo oldInfo = mAuthInfo;
            CloudAuthInfo newInfo = new CloudAuthInfo(cloudId, authToken);
            if (oldInfo != null && oldInfo.equals(newInfo))
                return true;

            if (control.setCloudId(newInfo)) {
                mAuthInfo = newInfo;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void onPostExecute(Boolean success) {
            setResult(success ? RESULT_OK : RESULT_CANCELED);
            finish();
        }
    }

    @JavascriptInterface
    public void setCloudId(String cloudId, String authToken) {
        new SetCloudIdTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, cloudId, authToken);
    }

    @Override
    public void onResume() {
        super.onResume();
        mEngine.start(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mEngine.stop(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Intent startIntent = getIntent();
        if (startIntent == null || startIntent.getAction() == null)
            return;

        switch (startIntent.getAction()) {
            case Intent.ACTION_VIEW:
                doActionView(startIntent.getData());
                break;
            case Intent.ACTION_MAIN:
                break;
            default:
                Log.w(MainActivity.LOG_TAG, "Received spurious intent " + startIntent.getAction());
        }
    }


    private void doSetServerAddress(final String host, final int port, final String authToken) {
        final ControlBinder control = mEngine.getControl();
        if (control == null)
            return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = control.setServerAddress(host, port, authToken);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showConfirmDialog(ok);
                    }
                });
            }
        });
    }

    private void maybeSetServerAddress(final String host, final int port, final String authToken) {
        new AlertDialog.Builder(this)
                .setMessage("Do you wish to pair with ThingEngine Server at "
                        + host + " on port " + port + "?")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doSetServerAddress(host, port, authToken);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_info)
                .show();
    }

    private void doActionView(Uri data) {
        if (!data.getScheme().equals("https") || !data.getHost().equals("thingengine.stanford.edu")) {
            Log.w(MainActivity.LOG_TAG, "Received spurious intent view " + data);
            return;
        }

        try {
            List<String> pathSegments = data.getPathSegments();
            if (pathSegments.size() == 4 && // 'qrcode', host, port, authToken
                "qrcode".equals(pathSegments.get(0))) {
                String host = pathSegments.get(1);
                int port = Integer.parseInt(pathSegments.get(2));
                String authToken = pathSegments.get(3);

                if (authToken.equals("undefined"))
                    authToken = null;
                maybeSetServerAddress(host, port, authToken);
            } else if (pathSegments.size() == 3 && // 'qrcode-cloud', cloud_id, auth_token
                       "qrcode-cloud".equals(pathSegments.get(0))) {
                setCloudId(pathSegments.get(1), pathSegments.get(2));
            } else {
                Log.w(MainActivity.LOG_TAG, "Received spurious intent view " + data);
                return;
            }
        } catch(NumberFormatException e) {
            Log.w(MainActivity.LOG_TAG, "Received spurious intent view " + data);
            return;
        }
    }
}
