package com.exteragram.messenger.utils.network;

import java.io.File;

public abstract class ImgurUtils {

    public static class ImgurResponse extends app.nimarkogram.messenger.utils.network.ImgurUtils.ImgurResponse {
        public ImgurResponse(String imageUrl, String imageId, String deleteHash) {
            super(imageUrl, imageId, deleteHash);
        }
    }

    public static ImgurResponse uploadImage(File file) {
        app.nimarkogram.messenger.utils.network.ImgurUtils.ImgurResponse r =
                app.nimarkogram.messenger.utils.network.ImgurUtils.uploadImage(file);
        if (r == null) {
            return null;
        }
        return new ImgurResponse(r.imageUrl, r.imageId, r.deleteHash);
    }

    public static boolean deleteImage(String deleteHash) {
        return app.nimarkogram.messenger.utils.network.ImgurUtils.deleteImage(deleteHash);
    }
}
