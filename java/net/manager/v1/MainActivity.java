/* 
NETLIFY MANAGER V1

Creator: Reyn
Tiktok: rhn.png
Status: terbuka, bebas dimodifikasi sesukamu
*/

// Packagw
package net.manager.v1;

// bBagian import
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;


public class MainActivity extends Activity {
// variabel global untuk webview, tokenfile, upload, dll.
    private WebView webView;
    private File tokenFile;
    private ValueCallback<Uri[]> uploadMessage;
    private final int FILECHOOSER_RESULTCODE = 1;
    private String pendingDeployType = ""; // "create" or "update"
    private String pendingSiteIdOrName = "";
    private String pendingToken = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
		// path token nya di 0/netlify/.netlify-token.json
        File root = Environment.getExternalStorageDirectory();

        // arahin ke folder "netlify"
        File dir = new File(root, "netlify"); 
        if (!dir.exists()) dir.mkdirs();

        // nama filenya ".netlify-token.json"
        tokenFile = new File(dir, ".netlify-token.json");
		
		
        // webview setting/pengaturan
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
		
		
        // ini buat bridge
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        webView.setWebViewClient(new WebViewClient());
        // web chrome client buat handle file picker
        webView.setWebChromeClient(new WebChromeClient() {
				public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
					if (uploadMessage != null) {
						uploadMessage.onReceiveValue(null);
					}
					uploadMessage = filePathCallback;
					Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					intent.setType("application/zip"); // zip only aja deh
					startActivityForResult(Intent.createChooser(intent, "Pilih File ZIP Project"), FILECHOOSER_RESULTCODE);
					return true;
				}
			});
			
		// ngambil html dari assets
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (uploadMessage == null) return;
            Uri result = (data == null || resultCode != RESULT_OK) ? null : data.getData();
            if (result != null) {
                // proses nya japan di bckground
                handleZipUpload(result);
            } else {
                uploadMessage.onReceiveValue(null);
                uploadMessage = null;
            }
        }
    }


// fungsi upload, make smartcheck(mastiin ada netlify.toml atau ga)
    private void handleZipUpload(final Uri fileUri) {
        showToast("Smart Check: Memeriksa struktur ZIP...");

        new Thread(new Runnable() {
				@Override
				public void run() {
					File tempFile = null;
					try {
						// proses zip, ntar inject netlify.toml klo blom ada
						// simpen ke file sementara
						tempFile = processZipWithSmartToml(fileUri);

						if (tempFile == null) throw new Exception("Gagal memproses ZIP");

						// proses upload
						String urlStr = "https://api.netlify.com/api/v1/sites";
						if(pendingDeployType.equals("update")) {
							urlStr += "/" + pendingSiteIdOrName + "/deploys";
						} else {
							urlStr += "?name=" + pendingSiteIdOrName;
						}
                         // metode nya post
						URL url = new URL(urlStr);
						HttpURLConnection conn = (HttpURLConnection) url.openConnection();
						conn.setRequestMethod("POST");
						conn.setRequestProperty("Authorization", "Bearer " + pendingToken);
						conn.setRequestProperty("Content-Type", "application/zip");
						conn.setDoOutput(true);
						// pake panjang file hasil olahan
						conn.setFixedLengthStreamingMode((int) tempFile.length());

						// kirim File Hasil Olahan
						OutputStream os = conn.getOutputStream();
						FileInputStream fis = new FileInputStream(tempFile);
						byte[] buffer = new byte[4096];
						int len;
						while ((len = fis.read(buffer)) > 0) {
							os.write(buffer, 0, len);
						}
						fis.close();
						os.flush();
						os.close();

						// baca respon
						final int code = conn.getResponseCode();
						BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
						StringBuilder response = new StringBuilder();
						String line;
						while ((line = br.readLine()) != null) response.append(line);

						// escape buat json
						final String resData = response.toString()
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\"", "\\\"")
                            .replace("\n", " ");
							
							
						// kasih p3mberitahuan klo ggal deploy
						final int finalCode = code;
						runOnUiThread(new Runnable() {
								@Override
								public void run() {
									if(finalCode >= 200 && finalCode < 300) {
										webView.loadUrl("javascript:app.onDeploySuccess('" + resData + "')");
									} else {
										showToast("Gagal Deploy: " + finalCode);
									}
								}
							});

					} catch (Exception e) {
						e.printStackTrace();
						final String msg = e.getMessage();
						runOnUiThread(new Runnable() { public void run() { showToast("Error: " + msg); } });
					} finally {
						// bwrsihin file sementaraa
						if (tempFile != null && tempFile.exists()) tempFile.delete();
						uploadMessage = null;
					}
				}
			}).start();
    }

    // logic smary toml injection
    private File processZipWithSmartToml(Uri sourceUri) throws IOException {
        // bkin file smntara dicache
        File cacheDir = getCacheDir();
        File tempFile = new File(cacheDir, "upload_ready.zip");

        InputStream is = getContentResolver().openInputStream(sourceUri);
        ZipInputStream zis = new ZipInputStream(is);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempFile));

        ZipEntry entry;
        boolean hasToml = false;
        byte[] buffer = new byte[4096];
		
		// salin isi zip lama, ke zip baru
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();

            // cek udh ada toml atau blom
            if (name.equalsIgnoreCase("netlify.toml") || name.endsWith("/netlify.toml")) {
                hasToml = true;
            }

            zos.putNextEntry(new ZipEntry(name));
            int len;
            while ((len = zis.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            zos.closeEntry();
        }
        zis.close();
        is.close();

        // klo toml gda, tambahin otomatis
        if (!hasToml) {
            // isi toml, spa rule
            String tomlContent = "[[redirects]]\n  from = \"/*\"\n  to = \"/index.html\"\n  status = 200\n";

            ZipEntry tomlEntry = new ZipEntry("netlify.toml");
            zos.putNextEntry(tomlEntry);
            zos.write(tomlContent.getBytes());
            zos.closeEntry();
        }

        zos.finish();
        zos.close();

        return tempFile;
    }


    // bridge js to java
    public class WebAppInterface {
        Context mContext;
        WebAppInterface(Context c) { mContext = c; }

        @JavascriptInterface
        public void showToast(String toast) {
            Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public String getTokenData() {
            if (!tokenFile.exists()) return "[]";
            try {
                FileInputStream fis = new FileInputStream(tokenFile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                return sb.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void saveTokenData(String json) {
            try {
                FileOutputStream fos = new FileOutputStream(tokenFile);
                fos.write(json.getBytes());
                fos.close();
                showToast("Akun tersimpan");
            } catch (Exception e) { showToast("Gagal simpan akun"); }
        }

        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Copied Text", text);
            clipboard.setPrimaryClip(clip);
            showToast("Teks disalin ke clipboard");
        }

		@JavascriptInterface
        public void downloadFile(final String urlStr, final String filename, final String token) {
            showToast("Memulai download " + filename + "...");

            new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							// req ke api netlify
							URL url = new URL(urlStr);
							HttpURLConnection conn = (HttpURLConnection) url.openConnection();
							conn.setRequestMethod("GET");
							conn.setRequestProperty("Authorization", "Bearer " + token);

							// matiin auto redirect
							conn.setInstanceFollowRedirects(false); 

							int code = conn.getResponseCode();
							if (code == 301 || code == 302 || code == 307) {
								String newUrl = conn.getHeaderField("Location");

								

								// link file asli
								url = new URL(newUrl);
								conn = (HttpURLConnection) url.openConnection();
								conn.setRequestMethod("GET");							
								conn.setInstanceFollowRedirects(true);

								// update kode status dari request kedua
								code = conn.getResponseCode();
							}

							// proses download zip
							if (code == 200) {
								File outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
								if (!outDir.exists()) outDir.mkdirs();
								File outFile = new File(outDir, filename);

								InputStream is = conn.getInputStream();
								FileOutputStream fos = new FileOutputStream(outFile);

								byte[] buffer = new byte[8192];
								int len;
								while ((len = is.read(buffer)) > 0) {
									fos.write(buffer, 0, len);
								}
								fos.close();
								is.close();

								final String path = outFile.getAbsolutePath();
								runOnUiThread(new Runnable() {
										public void run() {
											Toast.makeText(MainActivity.this, "Berhasil! Disimpan di Download", Toast.LENGTH_LONG).show();
										}
									});
							} else {
								final int errCode = code;
								final String errMsg = conn.getResponseMessage();
								runOnUiThread(new Runnable() { 
										public void run() { 
											showToast("Gagal: Code " + errCode + " (" + errMsg + ")\nCek URL/Token."); 
										} 
									});
							}

						} catch (final Exception e) {
							e.printStackTrace();
							final String err = e.getMessage();
							runOnUiThread(new Runnable() { public void run() { showToast("Error Java: " + err); } });
						}
					}
				}).start();
        }


      
        @JavascriptInterface
        public void triggerFilePicker(String type, String idOrName, String token) {
            pendingDeployType = type;
            pendingSiteIdOrName = idOrName;
            pendingToken = token;
            runOnUiThread(new Runnable() {
					@Override
					public void run() {
						webView.loadUrl("javascript:document.getElementById('fileInputTrigger').click()");
					}
				});
        }

        @JavascriptInterface
        public void apiRequest(final String urlStr, final String method, final String token, final String body, final String callbackId) {
            new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							URL url = new URL("https://api.netlify.com/api/v1" + urlStr);
							HttpURLConnection conn = (HttpURLConnection) url.openConnection();
							conn.setRequestMethod(method);
							conn.setRequestProperty("Authorization", "Bearer " + token);
							conn.setRequestProperty("Content-Type", "application/json");

							if (body != null && !body.isEmpty()) {
								conn.setDoOutput(true);
								OutputStream os = conn.getOutputStream();
								os.write(body.getBytes());
								os.flush(); os.close();
							}

							final int code = conn.getResponseCode();
							BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
							// fungsi resData(log)

							StringBuilder response = new StringBuilder();
							String line;
							while ((line = br.readLine()) != null) response.append(line);

							final String resData = response.toString()
                                .replace("\\", "\\\\") 
                                .replace("'", "\\'")   
                                .replace("\"", "\\\"")  
                                .replace("\n", " ")     
                                .replace("\r", "");

							final int finalCode = code;

							runOnUiThread(new Runnable() {
									@Override
									public void run() {
										webView.loadUrl("javascript:onApiResult('" + callbackId + "', " + finalCode + ", '" + resData + "')");
									}
								});

							//  catch
						} catch (Exception e) {
							final String err = e.getMessage();
							runOnUiThread(new Runnable() { public void run() {
										webView.loadUrl("javascript:onApiResult('" + callbackId + "', 500, '{\"error\":\"" + err + "\"}')");
									}});
						}
					}
				}).start();
        }
    }

	// tambahqn biar toast g err
    private void showToast(final String msg) {
        runOnUiThread(new Runnable() {
				@Override
				public void run() {
					Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
				}
			});
    }
}

