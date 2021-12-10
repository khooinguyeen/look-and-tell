package com.khooinguyeen.lookandtell;

import androidx.appcompat.app.AppCompatActivity;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.glutil.EglManager;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    /** Lật các khung xem trước máy ảnh theo chiều dọc theo mặc định, trước khi gửi chúng vào FrameProcessor
     * được xử lý trong biểu đồ MediaPipe và lật ngược các khung đã xử lý khi chúng
     * hiển thị. Điều này có thể cần thiết vì OpenGL đại diện cho hình ảnh giả sử nguồn gốc hình ảnh là
     * góc dưới cùng bên trái, trong khi MediaPipe nói chung giả định nguồn gốc hình ảnh là ở
     * góc trên bên trái.
     * LƯU Ý: sử dụng "flipFramesVerently" trong siêu dữ liệu tệp kê khai để ghi đè hành vi này.
     */
    private static final boolean FLIP_FRAMES_VERTICALLY = true;
    private static final String OUTPUT_LANDMARKS_STREAM_NAME = "holistic_landmarks";

    static {
        // Tải các thư viện cần thiết cho app
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (UnsatisfiedLinkError e) {
            // Một số ứng dụng yêu cầu openCV 4
            System.loadLibrary("opencv_java4");
        }
    }


    /** Gửi các khung xem trước máy ảnh vào biểu đồ MediaPipe để xử lý và hiển thị các khung đã xử lý
    * đóng khung vào Surface.*/
    protected FrameProcessor processor;

    /** Xử lý quyền truy cập máy ảnh thông qua thư viện hỗ trợ Jetpack CameraX. */
    protected CameraXPreviewHelper cameraHelper;

    /** {@link SurfaceTexture} nơi có thể truy cập các khung xem trước máy ảnh. */
    private SurfaceTexture previewFrameTexture;

    /** {@link SurfaceView} hiển thị các khung xem trước máy ảnh được xử lý bởi biểu đồ MediaPipe.*/
    private SurfaceView previewDisplayView;

    /** Tạo và quản lý EGLContext.*/
    private EglManager eglManager;

    /** Chuyển đổi đồ họa GL_TEXTURE_EXTERNAL_OES từ máy ảnh Android thành đồ họa thông thường.
     * được sử dụng bởi {@link FrameProcessor} và biểu đồ MediaPipe bên dưới.
     */
    private ExternalTextureConverter converter;

    // ApplicationInfo để truy xuất siêu dữ liệu được xác định trong manifest
    private ApplicationInfo applicationInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            applicationInfo =
                    getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot find application info: " + e);
        }

        previewDisplayView = new SurfaceView(this);
        setupPreviewDisplayView();

        // Khởi tạo trình quản lý nội dung để các thư viện gốc MediaPipe có thể truy cập vào nội dung ứng dụng, ví dụ:
        // đồ thị nhị phân.
        AndroidAssetUtil.initializeNativeAssetManager(this);
        eglManager = new EglManager(null);
        processor =
                new FrameProcessor(
                        this,
                        eglManager.getNativeContext(),
                        applicationInfo.metaData.getString("binaryGraphName"),
                        applicationInfo.metaData.getString("inputVideoStreamName"),
                        applicationInfo.metaData.getString("outputVideoStreamName")
                );

        processor
                .getVideoSurfaceOutput()
                .setFlipY(
                        applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));

        PermissionHelper.checkAndRequestCameraPermissions(this);

        if(Log.isLoggable(TAG, Log.VERBOSE)) {
            processor.addPacketCallback(
                    OUTPUT_LANDMARKS_STREAM_NAME,
                    (packet) -> {
                        Log.v(TAG, "received packet.");
                        List<NormalizedLandmarkList> holisticLandmark =
                                PacketGetter.getProtoVector(packet, NormalizedLandmarkList.parser());
                        Log.v(
                                TAG,
                                "[TS:"
                                        + packet.getTimestamp()
                                        + "] "
                                        + getHolisticLandmarksDebugString(holisticLandmark));
                    }
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        converter = new ExternalTextureConverter(eglManager.getContext());
        converter.setFlipY(
                applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        converter.setConsumer(processor);
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        converter.close();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void onCameraStarted(SurfaceTexture surfaceTexture) {
        previewFrameTexture = surfaceTexture;
        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView.setVisibility(View.VISIBLE);
    }

    protected Size cameraTargetResolution() {
        return null; // No preference and let the camera (helper) decide.
    }

    public void startCamera() {
        cameraHelper = new CameraXPreviewHelper();
        cameraHelper.setOnCameraStartedListener(
                surfaceTexture -> {
                    onCameraStarted(surfaceTexture);
                });
        CameraHelper.CameraFacing cameraFacing =
                applicationInfo.metaData.getBoolean("cameraFacingFront", false)
                        ? CameraHelper.CameraFacing.FRONT
                        : CameraHelper.CameraFacing.BACK;
        cameraHelper.startCamera(
                this, cameraFacing, /*surfaceTexture=*/ null, cameraTargetResolution());
    }

    protected Size computeViewSize(int width, int height) {
        return new Size(width, height);
    }

    protected void onPreviewDisplaySurfaceChanged(
            SurfaceHolder holder, int format, int width, int height) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.

        // Tính kích thước lý tưởng của màn hình xem trước máy ảnh (khu vực mà
        // khung xem trước máy ảnh được hiển thị trên đó, có khả năng chia tỷ lệ và xoay)
        // dựa trên kích thước của SurfaceView chứa màn hình.
        Size viewSize = computeViewSize(width, height);
        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
        boolean isCameraRotated = cameraHelper.isCameraRotated();

        // Connect the converter to the camera-preview frames as its input (via
        // previewFrameTexture), and configure the output width and height as the computed
        // display size.

        // Kết nối bộ chuyển đổi với khung xem trước máy ảnh làm đầu vào của nó (thông qua
        // previewFrameTexture), và định cấu hình chiều rộng và chiều cao đầu ra như được tính
        // kích thước hiển thị.
        converter.setSurfaceTextureAndAttachToGLContext(
                previewFrameTexture,
                isCameraRotated ? displaySize.getHeight() : displaySize.getWidth(),
                isCameraRotated ? displaySize.getWidth() : displaySize.getHeight());
    }

    private void setupPreviewDisplayView() {
        previewDisplayView.setVisibility(View.GONE);
        ViewGroup viewGroup = findViewById(R.id.preview_display_layout);
        viewGroup.addView(previewDisplayView);

        previewDisplayView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                            }

                            @Override
                            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                                onPreviewDisplaySurfaceChanged(holder, format, width, height);
                            }

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(null);
                            }
                        });
    }

    private String getHolisticLandmarksDebugString(List<NormalizedLandmarkList> holisticLandmarks) {
        if (holisticLandmarks.isEmpty()) {
            return "No hand landmarks";
        }
        String holisticLandmarksStr = "Number of hands detected: " + holisticLandmarks.size() + "\n";
        int holisticIndex = 0;
        for (NormalizedLandmarkList landmarks : holisticLandmarks) {
            holisticLandmarksStr +=
                    "\t#Hand landmarks for hand[" + holisticIndex + "]: " + landmarks.getLandmarkCount() + "\n";
            int landmarkIndex = 0;
            for (NormalizedLandmark landmark : landmarks.getLandmarkList()) {
                holisticLandmarksStr +=
                        "\t\tLandmark ["
                                + landmarkIndex
                                + "]: ("
                                + landmark.getX()
                                + ", "
                                + landmark.getY()
                                + ", "
                                + landmark.getZ()
                                + ")\n";
                ++landmarkIndex;
            }
            ++holisticIndex;
        }
        return holisticLandmarksStr;
    }
}