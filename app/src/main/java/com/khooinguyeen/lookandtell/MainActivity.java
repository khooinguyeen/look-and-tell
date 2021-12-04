package com.khooinguyeen.lookandtell;

import androidx.appcompat.app.AppCompatActivity;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;

import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.glutil.EglManager;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Lật các frame camera-preview
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

    static {
        // Load các thư viện native cần thiết
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch(UnsatisfiedLinkError e) {
            // Ngoài opencv_java3 thì phải load thêm ver 4 vì một số chức năng yêu cầu openCV 4
            System.loadLibrary("opencv_java4");
        }
    }

    // Gửi các frame camera-preview sang Mediapipe graph để xử lý, sau đó display những
    // frame được xử lý lên 1 cái {@link Surface}.
    protected FrameProcessor processor;
    // xử lý quyền truy cập máy ảnh thông qua thư viện hỗ trợ Jetpack {@link CameraX}.
    protected CameraXPreviewHelper cameraHelper;

    // {@link SurfaceTexture} nơi có thể truy cập các khung xem trước máy ảnh.
    private SurfaceTexture previewFrameTexture;

    // {@link SurfaceView} hiển thị khung xem trước máy ảnh được xử lý bởi biểu đồ MediaPipe.
    private SurfaceView previewDisplayView;

    // Tạo và quản lý {@link EGLContext}.
    private EglManager eglManager;

    // Chuyển đổi họa tiết GL_TEXTURE_EXTERNAL_OES từ máy ảnh Android thành họa tiết thông thường.
    // được sử dụng bởi {@link FrameProcessor} và biểu đồ MediaPipe bên dưới.
    private ExternalTextureConverter converter;

    // ApplicationInfo để truy xuất siêu dữ liệu được xác định trong tệp kê khai.
    private ApplicationInfo applicationInfo;

    @Override
    protected void onCreate(Bundle savedIntanceState) {
        super.onCreate(savedIntanceState);
        setContentView(R.layout.activity_main);

        try {
            applicationInfo =
                    getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch(PackageManager.NameNotFoundException e) {
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        converter = new ExternalTextureConverter(eglManager.getContext());
        converter.setFlipY(
                applicationInfo.metaData.getBoolean("flipFrameVertically", FLIP_FRAMES_VERTICALLY)
        );
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera();
        }
    }


}
