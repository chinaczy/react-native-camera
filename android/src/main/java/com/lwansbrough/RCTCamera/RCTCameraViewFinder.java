/**
 * Created by Fabrice Armisen (farmisen@gmail.com) on 1/3/16.
 */

package com.lwansbrough.RCTCamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.TextureView;
import android.os.AsyncTask;
import android.view.WindowManager;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.EnumSet;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

class RCTCameraViewFinder extends TextureView implements TextureView.SurfaceTextureListener, Camera.PreviewCallback ,Camera.AutoFocusCallback{
    private int _cameraType;
    private int _captureMode;
    private SurfaceTexture _surfaceTexture;
    private boolean _isStarting;
    private boolean _isStopping;
    private Camera _camera;
    private long autoFocusTime ;
    // concurrency lock for barcode scanner to avoid flooding the runtime
    public static volatile boolean barcodeScannerTaskLock = false;
    public static final String TAG ="xb_camera";
    // reader instance for the barcode scanner
    private final MultiFormatReader _multiFormatReader = new MultiFormatReader();

    public RCTCameraViewFinder(Context context, int type) {
        super(context);
        this.setSurfaceTextureListener(this);
        this._cameraType = type;
        if(RCTCamera.getInstance().isBarcodeScannerEnabled())
            this.initBarcodeReader(RCTCamera.getInstance().getBarCodeTypes());
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        _surfaceTexture = surface;
        startCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        _surfaceTexture = null;
        stopCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public double getRatio() {
        int width = RCTCamera.getInstance().getPreviewWidth(this._cameraType);
        int height = RCTCamera.getInstance().getPreviewHeight(this._cameraType);
        return ((float) width) / ((float) height);
    }

    public void setCameraType(final int type) {
        if (this._cameraType == type) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                stopPreview();
                _cameraType = type;
                startPreview();
            }
        }).start();
    }

    public void setCaptureMode(final int captureMode) {
        RCTCamera.getInstance().setCaptureMode(_cameraType, captureMode);
        this._captureMode = captureMode;
    }

    public void setCaptureQuality(String captureQuality) {
        RCTCamera.getInstance().setCaptureQuality(_cameraType, captureQuality);
    }

    public void setTorchMode(int torchMode) {
        RCTCamera.getInstance().setTorchMode(_cameraType, torchMode);
    }

    public void setFlashMode(int flashMode) {
        RCTCamera.getInstance().setFlashMode(_cameraType, flashMode);
    }

    private void startPreview() {
        if (_surfaceTexture != null) {
            startCamera();
        }
    }

    private void stopPreview() {
        if (_camera != null) {
            stopCamera();
        }
    }

    synchronized private void startCamera() {
        if (!_isStarting) {
            _isStarting = true;
            try {
                _camera = RCTCamera.getInstance().acquireCameraInstance(_cameraType);
                Camera.Parameters parameters = _camera.getParameters();
                // set autofocus
                List<String> focusModes = parameters.getSupportedFocusModes();
                 if(RCTCamera.getInstance().isBarcodeScannerEnabled()&&focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)){
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                     WindowManager manager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                     Display display = manager.getDefaultDisplay();
                     Point theScreenResolution = new Point();
                     display.getSize(theScreenResolution);
                     Point bestPreviewSize = findBestPreviewSizeValue(parameters, theScreenResolution);
                     if(bestPreviewSize!=null)
                        parameters.setPreviewSize(bestPreviewSize.x , bestPreviewSize.y);
                }else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
                // set picture size
                // defaults to max available size
                List<Camera.Size> supportedSizes;
                if (_captureMode == RCTCameraModule.RCT_CAMERA_CAPTURE_MODE_STILL) {
                    supportedSizes = parameters.getSupportedPictureSizes();
                } else if (_captureMode == RCTCameraModule.RCT_CAMERA_CAPTURE_MODE_VIDEO) {
                    supportedSizes = RCTCamera.getInstance().getSupportedVideoSizes(_camera);
                } else {
                    throw new RuntimeException("Unsupported capture mode:" + _captureMode);
                }
                Camera.Size optimalPictureSize = RCTCamera.getInstance().getBestSize(
                        supportedSizes,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE
                );
                parameters.setPictureSize(optimalPictureSize.width, optimalPictureSize.height);

                _camera.setParameters(parameters);
                _camera.setPreviewTexture(_surfaceTexture);
                _camera.startPreview();
                // send previews to `onPreviewFrame`
                _camera.setPreviewCallback(this);
            } catch (NullPointerException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
                stopCamera();
            } finally {
                _isStarting = false;
            }
        }
    }
    private static final int MIN_PREVIEW_PIXELS = 480 * 320; // normal screen
    private static final double MAX_ASPECT_DISTORTION = 0.15;
    public static Point findBestPreviewSizeValue(Camera.Parameters parameters, Point screenResolution) {

        List<Camera.Size> rawSupportedSizes = parameters.getSupportedPreviewSizes();
        if (rawSupportedSizes == null) {
            Log.w(TAG, "Device returned no supported preview sizes; using default");
            Camera.Size defaultSize = parameters.getPreviewSize();
            if (defaultSize == null) {
                throw new IllegalStateException("Parameters contained no preview size!");
            }
            return new Point(defaultSize.width, defaultSize.height);
        }

        if (Log.isLoggable(TAG, Log.INFO)) {
            StringBuilder previewSizesString = new StringBuilder();
            for (Camera.Size size : rawSupportedSizes) {
                previewSizesString.append(size.width).append('x').append(size.height).append(' ');
            }
            Log.i(TAG, "Supported preview sizes: " + previewSizesString);
        }

        double screenAspectRatio = screenResolution.x / (double) screenResolution.y;

        // Find a suitable size, with max resolution
        int maxResolution = 0;
        Camera.Size maxResPreviewSize = null;
        for (Camera.Size size : rawSupportedSizes) {
            int realWidth = size.width;
            int realHeight = size.height;
            int resolution = realWidth * realHeight;
            if (resolution < MIN_PREVIEW_PIXELS) {
                continue;
            }

            boolean isCandidatePortrait = realWidth < realHeight;
            int maybeFlippedWidth = isCandidatePortrait ? realHeight : realWidth;
            int maybeFlippedHeight = isCandidatePortrait ? realWidth : realHeight;
            double aspectRatio = maybeFlippedWidth / (double) maybeFlippedHeight;
            double distortion = Math.abs(aspectRatio - screenAspectRatio);
            if (distortion > MAX_ASPECT_DISTORTION) {
                continue;
            }

            if (maybeFlippedWidth == screenResolution.x && maybeFlippedHeight == screenResolution.y) {
                Point exactPoint = new Point(realWidth, realHeight);
                Log.i(TAG, "Found preview size exactly matching screen size: " + exactPoint);
                return exactPoint;
            }

            // Resolution is suitable; record the one with max resolution
            if (resolution > maxResolution) {
                maxResolution = resolution;
                maxResPreviewSize = size;
            }
        }

        // If no exact match, use largest preview size. This was not a great idea on older devices because
        // of the additional computation needed. We're likely to get here on newer Android 4+ devices, where
        // the CPU is much more powerful.
        if (maxResPreviewSize != null) {
            Point largestSize = new Point(maxResPreviewSize.width, maxResPreviewSize.height);
            Log.i(TAG, "Using largest suitable preview size: " + largestSize);
            return largestSize;
        }

        // If there is nothing at all suitable, return current preview size
        Camera.Size defaultPreview = parameters.getPreviewSize();
        if (defaultPreview == null) {
            throw new IllegalStateException("Parameters contained no preview size!");
        }
        Point defaultSize = new Point(defaultPreview.width, defaultPreview.height);
        Log.i(TAG, "No suitable preview sizes, using default: " + defaultSize);
        return defaultSize;
    }
    synchronized private void stopCamera() {
        if (!_isStopping) {
            _isStopping = true;
            try {
                if (_camera != null) {
                    _camera.stopPreview();
                    // stop sending previews to `onPreviewFrame`
                    _camera.setPreviewCallback(null);
                    RCTCamera.getInstance().releaseCameraInstance(_cameraType);
                    _camera = null;
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                _camera = null ;
                _isStopping = false;
            }
        }
    }

    /**
     * Parse barcodes as BarcodeFormat constants.
     *
     * Supports all iOS codes except [code138, code39mod43, itf14]
     *
     * Additionally supports [codabar, code128, maxicode, rss14, rssexpanded, upca, upceanextension]
     */
    private BarcodeFormat parseBarCodeString(String c) {
        if ("aztec".equals(c)) {
            return BarcodeFormat.AZTEC;
        } else if ("ean13".equals(c)) {
            return BarcodeFormat.EAN_13;
        } else if ("ean8".equals(c)) {
            return BarcodeFormat.EAN_8;
        } else if ("ean8".equals(c)) {
            return BarcodeFormat.EAN_8;
        } else if ("qr".equals(c)) {
            return BarcodeFormat.QR_CODE;
        } else if ("ean8".equals(c)) {
            return BarcodeFormat.EAN_8;
        } else if ("pdf417".equals(c)) {
            return BarcodeFormat.PDF_417;
        } else if ("upce".equals(c)) {
            return BarcodeFormat.UPC_E;
        } else if ("datamatrix".equals(c)) {
            return BarcodeFormat.DATA_MATRIX;
        } else if ("code39".equals(c)) {
            return BarcodeFormat.CODE_39;
        } else if ("code93".equals(c)) {
            return BarcodeFormat.CODE_93;
        } else if ("interleaved2of5".equals(c)) {
            return BarcodeFormat.ITF;
        } else if ("codabar".equals(c)) {
            return BarcodeFormat.CODABAR;
        } else if ("code128".equals(c)) {
            return BarcodeFormat.CODE_128;
        } else if ("maxicode".equals(c)) {
            return BarcodeFormat.MAXICODE;
        } else if ("rss14".equals(c)) {
            return BarcodeFormat.RSS_14;
        } else if ("rssexpanded".equals(c)) {
            return BarcodeFormat.RSS_EXPANDED;
        } else if ("upca".equals(c)) {
            return BarcodeFormat.UPC_A;
        } else if ("upceanextension".equals(c)) {
            return BarcodeFormat.UPC_EAN_EXTENSION;
        } else {
            android.util.Log.v("RCTCamera", "Unsupported code.. [" + c + "]");
            return null;
        }
    }

    /**
     * Initialize the barcode decoder.
     */
    private void initBarcodeReader(List<String> barCodeTypes) {

        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        EnumSet<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

        if (barCodeTypes != null) {
            for (String code : barCodeTypes) {
                BarcodeFormat format = parseBarCodeString(code);
                if (format != null) {
                    decodeFormats.add(format);
                }
            }
        }
        if(decodeFormats.isEmpty()){
            decodeFormats.add(BarcodeFormat.QR_CODE) ;
        }
        hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
        _multiFormatReader.setHints(hints);
    }

    /**
     * Spawn a barcode reader task if
     *  - the barcode scanner is enabled (has a onBarCodeRead function)
     *  - one isn't already running
     *
     * See {Camera.PreviewCallback}
     */
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (RCTCamera.getInstance().isBarcodeScannerEnabled() && !RCTCameraViewFinder.barcodeScannerTaskLock) {

            RCTCameraViewFinder.barcodeScannerTaskLock = true;
            new ReaderThread(camera.getParameters().getPreviewSize(), data).start();
            long time = System.currentTimeMillis() ;
            if(autoFocusTime == 0 ){
                autoFocusTime = time ;
            }
            else if( time - autoFocusTime > 2000)
            {
                Log.e("test","auto focus ...");
                autoFocusTime = time  ;
                camera.autoFocus(this);
            }
        }

    }
    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        Log.e("ReactNativeJS" , "auto focus = " + success ) ;
    }

    private class ReaderThread extends Thread {
        private byte[] imageData;
        private final Camera.Size size;

        ReaderThread(Camera.Size size, byte[] imageData) {
            this.size = size;
            this.imageData = imageData;
        }
        public Bitmap rawByteArray2RGBABitmap2(byte[] data, int width, int height) {
            int frameSize = width * height;
            int[] rgba = new int[frameSize];

            for (int i = 0; i < height; i++)
                for (int j = 0; j < width; j++) {
                    int y = (0xff & ((int) data[i * width + j]));
                    int u = (0xff & ((int) data[frameSize + (i >> 1) * width + (j & ~1) + 0]));
                    int v = (0xff & ((int) data[frameSize + (i >> 1) * width + (j & ~1) + 1]));
                    y = y < 16 ? 16 : y;

                    int r = Math.round(1.164f * (y - 16) + 1.596f * (v - 128));
                    int g = Math.round(1.164f * (y - 16) - 0.813f * (v - 128) - 0.391f * (u - 128));
                    int b = Math.round(1.164f * (y - 16) + 2.018f * (u - 128));

                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    g = g < 0 ? 0 : (g > 255 ? 255 : g);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);

                    rgba[i * width + j] = 0xff000000 + (b << 16) + (g << 8) + r;
                }

            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.setPixels(rgba, 0 , width, 0, 0, width, height);
            return bmp;
        }
        @Override
        public void run() {
            int width = size.width;
            int height = size.height;
            long time = System.currentTimeMillis() ;
            // rotate for zxing if orientation is portrait
            if (RCTCamera.getInstance().getActualDeviceOrientation() == 0) {
              byte[] rotated = new byte[imageData.length];
              for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                  rotated[x * height + height - y - 1] = imageData[x + y * width];
                }
              }
              width = size.height;
              height = size.width;
              imageData = rotated;
            }

//            DisplayMetrics dm = getResources().getDisplayMetrics() ;
//
//            float scanWidth = 240 * dm.density ;
//			float imgScale = width / dm.widthPixels ;
//            int imgWidth = (int) (scanWidth * imgScale);
//            int imgTop = imgWidth / 2 ;
//            int imgLeft = (int) ((width - imgWidth) / 2);
//			Log.e("test","width  = " + dm.widthPixels +" img width = " + width  +" img height = "+height +" img top3 " + imgTop +" img left = " + imgLeft ) ;
//            byte []newImg = new byte[imgWidth*imgWidth];
//            for(int i = 0 ; i < imgWidth ; i++){
//                for(int j = 0 ; j < imgWidth ; j++){
//                    newImg[i*imgWidth+j]=imageData[(i+imgTop)*width + imgLeft + j];
//
//                }
//            }
            boolean qrSuccess = false ;
            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(imageData, width, height, 0, 0, width, height, false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result result = _multiFormatReader.decodeWithState(bitmap);
                qrSuccess = true ;
                ReactContext reactContext = RCTCameraModule.getReactContextSingleton();
                WritableMap event = Arguments.createMap();
                event.putString("data", result.getText());
                event.putString("type", result.getBarcodeFormat().toString());
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("CameraBarCodeReadAndroid", event);

        } catch (Throwable t) {
            // meh
            qrSuccess = false ;
        } finally {
            Log.e("test","spend time = " +(System.currentTimeMillis() - time )) ;
            _multiFormatReader.reset();
            if(!qrSuccess)
                RCTCameraViewFinder.barcodeScannerTaskLock = false;
        }
        }
    }
}
