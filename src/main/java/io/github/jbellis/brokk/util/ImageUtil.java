package io.github.jbellis.brokk.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;

public class ImageUtil {
    private static final Logger logger = LogManager.getLogger(ImageUtil.class);

    public static BufferedImage toBuffered(Image img) {
        if (img == null) return null; // Handle null input
        if (img instanceof BufferedImage bi) {
            return bi;
        }
        BufferedImage bimage = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D bGr = bimage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();
        return bimage;
    }

    /**
     * Converts an AWT Image to a LangChain4j Image by encoding it as base64 PNG.
     * 
     * @param awtImage The AWT Image to convert
     * @return A LangChain4j Image
     * @throws IOException If image conversion fails
     */
    public static dev.langchain4j.data.image.Image toL4JImage(Image awtImage) throws IOException {
        if (awtImage == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        
        // Convert to BufferedImage if needed
        BufferedImage bufferedImage = toBuffered(awtImage);
        
        // Convert to PNG bytes
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();
            
            // Encode as base64 and create LangChain4j Image using the builder pattern
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return dev.langchain4j.data.image.Image.builder()
                    .base64Data(base64)
                    .mimeType("image/png")
                    .build();
        }
    }

    /**
     * Checks if the given URI likely points to an image by sending a HEAD request
     * and checking the Content-Type header.
     *
     * @param uri    The URI to check.
     * @param client The OkHttpClient to use for the request.
     * @return true if the URI content type starts with "image/", false otherwise.
     */
    public static boolean isImageUri(URI uri, OkHttpClient client) {
        Request request = new Request.Builder()
                .url(uri.toString())
                .head() // Send a HEAD request
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String contentType = response.header("Content-Type");
                if (contentType != null) {
                    logger.debug("URL {} Content-Type: {}", uri, contentType);
                    return contentType.toLowerCase().startsWith("image/");
                } else {
                    logger.warn("URL {} did not return a Content-Type header.", uri);
                }
            } else {
                logger.warn("HEAD request to {} failed with code: {}", uri, response.code());
            }
        } catch (IOException e) {
            // Log common issues like UnknownHostException or ConnectTimeoutException at a less severe level
            if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException) {
                logger.warn("IOException during HEAD request to {}: {}", uri, e.getMessage());
            } else {
                logger.error("IOException during HEAD request to {}: {}", uri, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Downloads an image from the given URI.
     *
     * @param uri    The URI to download the image from.
     * @param client The OkHttpClient to use for the request (though ImageIO uses its own mechanism).
     *               It's included for consistency if we later switched to OkHttp for download.
     * @return The downloaded Image, or null if an error occurred or it's not a valid image.
     */
    @Nullable
    public static Image downloadImage(URI uri, OkHttpClient client) {
        // Note: ImageIO.read(URL) handles its own connection.
        // The OkHttpClient isn't directly used here for the download itself but is part of the API
        // in case future implementations want to use it (e.g., for direct byte streaming with OkHttp).
        try {
            // It's good practice to ensure the URI is an image first using isImageUri if this method is called externally without prior check.
            // However, ImageIO.read will also return null if it cannot decode the content.
            return ImageIO.read(uri.toURL());
        } catch (IOException e) {
            logger.warn("Failed to fetch or decode image from URL {}: {}.", uri, e.getMessage());
            return null;
        }
    }
}
