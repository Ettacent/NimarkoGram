package app.nimarkogram.messenger.utils.network;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public abstract class ImgurUtils {

    private static final String CLIENT_ID = "545ed8be64bb3b0";

    public static class ImgurResponse {
        public String imageUrl;
        public String imageId;
        public String deleteHash;

        public ImgurResponse(String imageUrl, String imageId, String deleteHash) {
            this.imageUrl = imageUrl;
            this.imageId = imageId;
            this.deleteHash = deleteHash;
        }
    }

    private static String getMimeTypeFromFile(File file) {
        String lowerCase = file.getName().toLowerCase();
        if (lowerCase.endsWith(".png")) {
            return "image/png";
        }
        if (lowerCase.endsWith(".jpg") || lowerCase.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerCase.endsWith(".gif")) {
            return "image/gif";
        }
        if (lowerCase.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lowerCase.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerCase.endsWith(".tiff") || lowerCase.endsWith(".tif")) {
            return "image/tiff";
        }
        return null;
    }

    public static ImgurResponse uploadImage(File file) {
        if (file == null) {
            return null;
        }
        String mimeType = getMimeTypeFromFile(file);
        if (mimeType == null) {
            return null;
        }

        String boundary = "----NimarkoGramImgur" + System.currentTimeMillis();
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.imgur.com/3/image");
            connection = (HttpURLConnection) url.openConnection();
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Authorization", "Client-ID " + CLIENT_ID);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            DataOutputStream request = new DataOutputStream(connection.getOutputStream());
            try {
                
                request.writeBytes(twoHyphens + boundary + lineEnd);
                request.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\""
                        + file.getName() + "\"" + lineEnd);
                request.writeBytes("Content-Type: " + mimeType + lineEnd);
                request.writeBytes(lineEnd);

                FileInputStream fileInputStream = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                        request.write(buffer, 0, bytesRead);
                    }
                } finally {
                    try {
                        fileInputStream.close();
                    } catch (Exception ignore) {
                    }
                }

                request.writeBytes(lineEnd);
                request.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                request.flush();
            } finally {
                try {
                    request.close();
                } catch (Exception ignore) {
                }
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                FileLog.e("ImgurUtils: unexpected code " + responseCode);
                return null;
            }

            String body = readStream(connection.getInputStream());
            if (body == null) {
                return null;
            }

            try {
                JSONObject data = new JSONObject(body).getJSONObject("data");
                return new ImgurResponse(
                        data.getString("link"),
                        data.getString("id"),
                        data.getString("deletehash"));
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    public static boolean deleteImage(String deleteHash) {
        if (deleteHash == null) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.imgur.com/3/image/" + deleteHash);
            connection = (HttpURLConnection) url.openConnection();
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("DELETE");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Authorization", "Client-ID " + CLIENT_ID);

            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static String readStream(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
