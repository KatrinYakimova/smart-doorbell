package com.ltst.katrin.doorbell;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import com.google.android.things.contrib.driver.button.Button;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.VisionRequestInitializer;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.EntityAnnotation;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class DoorbellActivity extends Activity {

    private static final java.lang.String CLOUD_VISION_API_KEY = "AIzaSyAIQpTSfjTXseEG7sJlmMaE-r7fHUioOI0";

    private static final String LABEL_DETECTION = "LABEL_DETECTION";

    private static final int MAX_LABEL_RESULTS = 10;

    // Construct the Vision API instance
    HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
    JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    VisionRequestInitializer initializer = new VisionRequestInitializer(CLOUD_VISION_API_KEY);
    private String gpioPinName = "BCM21";
    private Button button;
    /**
     * A Handler for running tasks in the background.
     */
    private Handler backgroundHandler;
    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread backgroundThread;
    private DoorbellCamera camera;
    private Vision vision;

    private Handler cloudVisionHandler;
    private HandlerThread cloudVisionHandlerThread;
    private FirebaseDatabase database;
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    // Get the raw image bytes
                    android.media.Image image = reader.acquireLatestImage();
                    ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                    final byte[] imageBytes = new byte[imageBuf.remaining()];
                    imageBuf.get(imageBytes);
                    image.close();

                    onPictureTaken(imageBytes);
                }
            };

    public static Map<String, Float> annotateImage(byte[] imageBytes) throws IOException {
        // Construct the Vision API instance
        HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        VisionRequestInitializer initializer = new VisionRequestInitializer(CLOUD_VISION_API_KEY);
        Vision vision = new Vision.Builder(httpTransport, jsonFactory, null)
                .setVisionRequestInitializer(initializer)
                .build();

        // Create the image request
        AnnotateImageRequest imageRequest = new AnnotateImageRequest();
        Image img = new Image();
        img.encodeContent(imageBytes);
        imageRequest.setImage(img);

        // Add the features we want
        Feature labelDetection = new Feature();
        labelDetection.setType(LABEL_DETECTION);
        labelDetection.setMaxResults(MAX_LABEL_RESULTS);
        imageRequest.setFeatures(Collections.singletonList(labelDetection));

        // Batch and execute the request
        BatchAnnotateImagesRequest requestBatch = new BatchAnnotateImagesRequest();
        requestBatch.setRequests(Collections.singletonList(imageRequest));
        BatchAnnotateImagesResponse response = vision.images()
                .annotate(requestBatch)
                // Due to a bug: requests to Vision API containing large images fail when GZipped.
                .setDisableGZipContent(true)
                .execute();

        return convertResponseToMap(response);
    }

    private static Map<String, Float> convertResponseToMap(BatchAnnotateImagesResponse response) {
        Map<String, Float> annotations = new HashMap<>();

        // Convert response into a readable collection of annotations
        List<EntityAnnotation> labels = response.getResponses().get(0).getLabelAnnotations();
        if (labels != null) {
            for (EntityAnnotation label : labels) {
                annotations.put(label.getDescription(), label.getScore());
            }
        }

        return annotations;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        camera = DoorbellCamera.getInstance();
        camera.initializeCamera(this, backgroundHandler, mOnImageAvailableListener);

        try {
            // high signal indicates the button is pressed
// use with a pull-down resistor
            button = new Button(gpioPinName,
                    // high signal indicates the button is pressed
                    // use with a pull-down resistor
                    Button.LogicState.PRESSED_WHEN_HIGH
            );
            button.setOnButtonEventListener(new Button.OnButtonEventListener() {
                @Override
                public void onButtonEvent(Button button, boolean pressed) {
                    if (pressed) {
                        camera.takePicture();
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
/////////

        vision = new Vision.Builder(httpTransport, jsonFactory, null)
                .setVisionRequestInitializer(initializer)
                .build();

        database = FirebaseDatabase.getInstance();

        startBackgroundThread();
        startCloudVisionThread();
    }

    private void startCloudVisionThread() {
        cloudVisionHandlerThread = new HandlerThread("CloudThread");
        cloudVisionHandlerThread.start();
        cloudVisionHandler = new Handler(cloudVisionHandlerThread.getLooper());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        camera.shutDown();
        try {
            button.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        backgroundThread.quitSafely();
    }

    private void onPictureTaken(byte[] imageBytes) {
        if (imageBytes != null) {

            // ...process the captured image...
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            // Compress to JPEG
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
            final byte[] imageBytesCompressed = byteArrayOutputStream.toByteArray();


            cloudVisionHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Map<String, Float> annotations = annotateImage(imageBytesCompressed);
                        Set<Map.Entry<String, Float>> entries = annotations.entrySet();
                        for (Map.Entry<String, Float> entry : entries) {
                            Log.d(entry.getKey(), entry.getValue().toString());
                        }

                        final DatabaseReference log = database.getReference("logs").push();
                        log.child("timestamp").setValue(ServerValue.TIMESTAMP);

                        // Save image data Base64 encoded
                        String encoded = Base64.encodeToString(imageBytesCompressed,
                                Base64.NO_WRAP | Base64.URL_SAFE);
                        log.child("image").setValue(encoded);

                        if (annotations != null) {
                            log.child("annotations").setValue(annotations);
                        }

                    } catch (IOException e) {
                        Log.e("Exception annotating", e.getMessage());
                    }

                }
            });

            Log.e("OK", "i'm done");
        }
    }

    /**
     * Starts a background thread and its Handler.
     */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("InputThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
}
