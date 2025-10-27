package com.ahm.capacitor.camera.preview;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.Camera;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.util.List;

import org.json.JSONArray;

@CapacitorPlugin(
    name = "CameraPreview",
    permissions = {
        @Permission(
            strings = { android.Manifest.permission.CAMERA },
            alias = CameraPreview.CAMERA_PERMISSION_ALIAS
        ),
        @Permission(
            strings = { android.Manifest.permission.RECORD_AUDIO },
            alias = CameraPreview.AUDIO_PERMISSION_ALIAS
        )
    }
)
public class CameraPreview extends Plugin implements CameraActivity.CameraPreviewListener {

    static final String CAMERA_PERMISSION_ALIAS = "camera";
    static final String AUDIO_PERMISSION_ALIAS = "audio";

    private static String VIDEO_FILE_PATH = "";
    private static String VIDEO_FILE_EXTENSION = ".mp4";

    private String captureCallbackId = "";
    private String snapshotCallbackId = "";
    private String recordCallbackId = "";
    private String cameraStartCallbackId = "";

    // keep track of previously specified orientation to support locking orientation:
    private int previousOrientationRequest = -1;

    private CameraActivity fragment;
    private int containerViewId = 20;

    @PluginMethod
    public void start(PluginCall call) {
        boolean camGranted = PermissionState.GRANTED.equals(getPermissionState(CAMERA_PERMISSION_ALIAS));
        boolean micGranted = PermissionState.GRANTED.equals(getPermissionState(AUDIO_PERMISSION_ALIAS));

        if (camGranted && micGranted) {
            startCamera(call);
        } else {
            // we have to save this call, so after the user responds
            // we continue the flow in handleCameraPermissionResult(...)
            bridge.saveCall(call);
            requestPermissionForAliases(
                new String[]{ CAMERA_PERMISSION_ALIAS, AUDIO_PERMISSION_ALIAS },
                call,
                "handleCameraPermissionResult"
            );
        }
    }

    @PluginMethod
    public void flip(PluginCall call) {
        try {
            fragment.switchCamera();
            call.resolve();
        } catch (Exception e) {
            Logger.debug(getLogTag(), "Camera flip exception: " + e);
            call.reject("failed to flip camera");
        }
    }

    @PluginMethod
    public void setOpacity(PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }

        bridge.saveCall(call);
        Float opacity = call.getFloat("opacity", 1F);
        fragment.setOpacity(opacity);
    }

    @PluginMethod
    public void capture(PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }
        bridge.saveCall(call);
        captureCallbackId = call.getCallbackId();

        Integer quality = call.getInt("quality", 85);
        // Image Dimensions - Optional
        Integer width = call.getInt("width", 0);
        Integer height = call.getInt("height", 0);
        fragment.takePicture(width, height, quality);
    }

    @PluginMethod
    public void captureSample(PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }
        bridge.saveCall(call);
        snapshotCallbackId = call.getCallbackId();

        Integer quality = call.getInt("quality", 85);
        fragment.takeSnapshot(quality);
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        bridge
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        FrameLayout containerView = getBridge().getActivity().findViewById(containerViewId);

                        // allow orientation changes after closing camera:
                        getBridge().getActivity().setRequestedOrientation(previousOrientationRequest);

                        if (containerView != null) {
                            ((ViewGroup) getBridge().getWebView().getParent()).removeView(containerView);
                            getBridge().getWebView().setBackgroundColor(Color.WHITE);
                            FragmentManager fragmentManager = getActivity().getFragmentManager();
                            FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                            fragmentTransaction.remove(fragment);
                            fragmentTransaction.commit();
                            fragment = null;

                            call.resolve();
                        } else {
                            call.reject("camera already stopped");
                        }
                    }
                }
            );
    }

    @PluginMethod
    public void getSupportedFlashModes(PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }

        Camera camera = fragment.getCamera();
        Camera.Parameters params = camera.getParameters();
        List<String> supportedFlashModes;
        supportedFlashModes = params.getSupportedFlashModes();
        JSONArray jsonFlashModes = new JSONArray();

        if (supportedFlashModes != null) {
            for (int i = 0; i < supportedFlashModes.size(); i++) {
                jsonFlashModes.put(new String(supportedFlashModes.get(i)));
            }
        }

        JSObject jsObject = new JSObject();
        jsObject.put("result", jsonFlashModes);
        call.resolve(jsObject);
    }

    @PluginMethod
    public void setFlashMode(PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }

        String flashMode = call.getString("flashMode");
        if (flashMode == null || flashMode.isEmpty() == true) {
            call.reject("flashMode required parameter is missing");
            return;
        }

        Camera camera = fragment.getCamera();
        Camera.Parameters params = camera.getParameters();

        List<String> supportedFlashModes;
        supportedFlashModes = camera.getParameters().getSupportedFlashModes();
        if (supportedFlashModes.indexOf(flashMode) > -1) {
            params.setFlashMode(flashMode);
        } else {
            call.reject("Flash mode not recognised: " + flashMode);
            return;
        }

        fragment.setCameraParameters(params);

        call.resolve();
    }

    @PluginMethod
    public void startRecordVideo(final PluginCall call) {
        // Check again here in case app logic tries to record later without starting camera in the same session.
        boolean camGranted = PermissionState.GRANTED.equals(getPermissionState(CAMERA_PERMISSION_ALIAS));
        boolean micGranted = PermissionState.GRANTED.equals(getPermissionState(AUDIO_PERMISSION_ALIAS));

        if (!camGranted || !micGranted) {
            // we save this call and request again — on modern Android,
            // RECORD_AUDIO might have been revoked while app was backgrounded
            bridge.saveCall(call);
            requestPermissionForAliases(
                new String[]{ CAMERA_PERMISSION_ALIAS, AUDIO_PERMISSION_ALIAS },
                call,
                "handleRecordVideoPermissionResult"
            );
            return;
        }

        actuallyStartRecordVideo(call);
    }

    @PermissionCallback
    private void handleRecordVideoPermissionResult(PluginCall call) {
        boolean camGranted = PermissionState.GRANTED.equals(getPermissionState(CAMERA_PERMISSION_ALIAS));
        boolean micGranted = PermissionState.GRANTED.equals(getPermissionState(AUDIO_PERMISSION_ALIAS));

        if (!camGranted || !micGranted) {
            call.reject("Permission failed: camera/mic not granted.");
            return;
        }

        actuallyStartRecordVideo(call);
    }

    // this is just your previous logic from startRecordVideo(...) moved into a helper
    private void actuallyStartRecordVideo(final PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }

        final String filename = "videoTmp";
        VIDEO_FILE_PATH = getActivity().getCacheDir().toString() + "/";

        final String position = call.getString("position", "front");
        final Integer width = call.getInt("width", 0);
        final Integer height = call.getInt("height", 0);
        final Boolean withFlash = call.getBoolean("withFlash", false);
        final Integer maxDuration = call.getInt("maxDuration", 0);

        // Save call so we resolve in callbacks later
        bridge.saveCall(call);
        recordCallbackId = call.getCallbackId();

        bridge
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        // NOTE: keep your same call to fragment.startRecord()
                        // including audio. We are NOT stripping audio now.
                        fragment.startRecord(
                            getFilePath(filename),
                            position,
                            width,
                            height,
                            70,           // quality or bitrate param you had
                            withFlash,
                            maxDuration
                        );
                    }
                }
            );

        // we DO NOT resolve here; we resolve in onStartRecordVideo()/onStopRecordVideo()
        call.resolve();
    }

    @PluginMethod
    public void stopRecordVideo(PluginCall call) {
        if (this.hasCamera(call) == false) {
            call.reject("Camera is not running");
            return;
        }

        System.out.println("stopRecordVideo - Callbackid=" + call.getCallbackId());

        bridge.saveCall(call);
        recordCallbackId = call.getCallbackId();

        // bridge.getActivity().runOnUiThread(new Runnable() {
        //     @Override
        //     public void run() {
        //         fragment.stopRecord();
        //     }
        // });

        fragment.stopRecord();
        // call.resolve();
    }

    @PluginMethod
    public void isCameraStarted(PluginCall call) {
        boolean isCameraStarted = hasCamera(call);
        JSObject ret = new JSObject();
        ret.put("value", isCameraStarted);
        call.resolve(ret);
    }

    @PermissionCallback
    private void handleCameraPermissionResult(PluginCall call) {
        boolean camGranted = PermissionState.GRANTED.equals(getPermissionState(CAMERA_PERMISSION_ALIAS));
        boolean micGranted = PermissionState.GRANTED.equals(getPermissionState(AUDIO_PERMISSION_ALIAS));

        if (camGranted && micGranted) {
            startCamera(call);
        } else {
            Logger.debug(
                getLogTag(),
                "User denied camera/mic permission: cam=" + getPermissionState(CAMERA_PERMISSION_ALIAS)
                    + " mic=" + getPermissionState(AUDIO_PERMISSION_ALIAS)
            );
            call.reject("Permission failed: user denied access to camera/microphone.");
        }
    }

    private void startCamera(final PluginCall call) {
        String position = call.getString("position");

        if (position == null || position.isEmpty() || "rear".equals(position)) {
            position = "back";
        } else {
            position = "front";
        }

        final Integer x = call.getInt("x", 0);
        final Integer y = call.getInt("y", 0);
        final Integer width = call.getInt("width", 0);
        final Integer height = call.getInt("height", 0);
        final Integer paddingBottom = call.getInt("paddingBottom", 0);
        final Boolean toBack = call.getBoolean("toBack", false);
        final Boolean storeToFile = call.getBoolean("storeToFile", false);
        final Boolean enableOpacity = call.getBoolean("enableOpacity", false);
        final Boolean enableZoom = call.getBoolean("enableZoom", false);
        final Boolean disableExifHeaderStripping = call.getBoolean("disableExifHeaderStripping", true);
        final Boolean lockOrientation = call.getBoolean("lockAndroidOrientation", false);
        previousOrientationRequest = getBridge().getActivity().getRequestedOrientation();

        fragment = new CameraActivity();
        fragment.setEventListener(this);
        fragment.defaultCamera = position;
        fragment.tapToTakePicture = false;
        fragment.dragEnabled = false;
        fragment.tapToFocus = true;
        fragment.disableExifHeaderStripping = disableExifHeaderStripping;
        fragment.storeToFile = storeToFile;
        fragment.toBack = toBack;
        fragment.enableOpacity = enableOpacity;
        fragment.enableZoom = enableZoom;

        bridge
            .getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        DisplayMetrics metrics = getBridge().getActivity().getResources().getDisplayMetrics();
                        // lock orientation if specified in options:
                        if (lockOrientation) {
                            getBridge().getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                        }

                        // offset
                        int computedX = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, metrics);
                        int computedY = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, metrics);

                        // size
                        Integer computedWidth = null;
                        Integer computedHeight = null;
                        int computedPaddingBottom = 0;

                        if (paddingBottom != 0) {
                            computedPaddingBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, paddingBottom, metrics);
                        }

                        if (width != 0) {
                            computedWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width, metrics);
                        }

                        if (height != 0) {
                            computedHeight =
                                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height, metrics) - computedPaddingBottom;
                        }

                        fragment.setRect(computedX, computedY, computedWidth, computedHeight);

                        FrameLayout containerView = getBridge().getActivity().findViewById(containerViewId);
                        if (containerView == null) {
                            containerView = new FrameLayout(getActivity().getApplicationContext());
                            containerView.setId(containerViewId);

                            getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
                            ((ViewGroup) getBridge().getWebView().getParent()).addView(containerView);
                            if (toBack == true) {
                                getBridge().getWebView().getParent().bringChildToFront(getBridge().getWebView());
                                setupBroadcast();
                            }

                            FragmentManager fragmentManager = getBridge().getActivity().getFragmentManager();
                            FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                            fragmentTransaction.add(containerView.getId(), fragment);
                            fragmentTransaction.commit();

                            // NOTE: we don't return invoke call.resolve here because it must be invoked in onCameraStarted
                            // otherwise the plugin start method might resolve/return before the camera is actually set in CameraActivity
                            // onResume method (see this line mCamera = Camera.open(defaultCameraId);) and the next subsequent plugin
                            // method invocations (for example, getSupportedFlashModes) might fails with "Camera is not running" error
                            // because camera is not available yet and hasCamera method will return false
                            // Please also see https://developer.android.com/reference/android/hardware/Camera.html#open%28int%29
                            bridge.saveCall(call);
                            cameraStartCallbackId = call.getCallbackId();
                        } else {
                            call.reject("camera already started");
                        }
                    }
                }
            );
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
    }

    @Override
    public void onPictureTaken(String originalPicture) {
        JSObject jsObject = new JSObject();
        jsObject.put("value", originalPicture);
        bridge.getSavedCall(captureCallbackId).resolve(jsObject);
    }

    @Override
    public void onPictureTakenError(String message) {
        bridge.getSavedCall(captureCallbackId).reject(message);
    }

    @Override
    public void onSnapshotTaken(String originalPicture) {
        JSObject jsObject = new JSObject();
        jsObject.put("value", originalPicture);
        bridge.getSavedCall(snapshotCallbackId).resolve(jsObject);
    }

    @Override
    public void onSnapshotTakenError(String message) {
        bridge.getSavedCall(snapshotCallbackId).reject(message);
    }

    @Override
    public void onFocusSet(int pointX, int pointY) {}

    @Override
    public void onFocusSetError(String message) {}

    @Override
    public void onBackButton() {}

    @Override
    public void onCameraStarted() {
        PluginCall pluginCall = bridge.getSavedCall(cameraStartCallbackId);
        pluginCall.resolve();
        bridge.releaseCall(pluginCall);
    }

    @Override
    public void onStartRecordVideo() {}

    @Override
    public void onStartRecordVideoError(String message) {
        bridge.getSavedCall(recordCallbackId).reject(message);
    }

    @Override
    public void onStopRecordVideo(String file) {
        PluginCall pluginCall = bridge.getSavedCall(recordCallbackId);
        JSObject jsObject = new JSObject();
        jsObject.put("videoFilePath", file);
        pluginCall.resolve(jsObject);
    }

    @Override
    public void onStopRecordVideoError(String error) {
        bridge.getSavedCall(recordCallbackId).reject(error);
    }

    private boolean hasView(PluginCall call) {
        if (fragment == null) {
            return false;
        }

        return true;
    }

    private boolean hasCamera(PluginCall call) {
        if (this.hasView(call) == false) {
            return false;
        }

        if (fragment.getCamera() == null) {
            return false;
        }

        return true;
    }

    private String getFilePath(String filename) {
        String fileName = filename;

        int i = 1;

        while (new File(VIDEO_FILE_PATH + fileName + VIDEO_FILE_EXTENSION).exists()) {
            // Add number suffix if file exists
            fileName = filename + '_' + i;
            i++;
        }

        return VIDEO_FILE_PATH + fileName + VIDEO_FILE_EXTENSION;
    }

    private void setupBroadcast() {
        /** When touch event is triggered, relay it to camera view if needed so it can support pinch zoom */

        getBridge().getWebView().setClickable(true);
        getBridge()
            .getWebView()
            .setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if ((null != fragment) && (fragment.toBack == true)) {
                            fragment.frameContainerLayout.dispatchTouchEvent(event);
                        }
                        return false;
                    }
                }
            );
    }
}
